"""
gRPC 视频嵌入服务端
只暴露一个方法: GetVideoEmbedding(video_url, title) -> embedding[512]

流程:
  1. 下载视频
  2. ffmpeg 动态抽帧
  3. CLIP ViT-L/14 → 视觉向量 (768)
  4. EasyOCR 提文字 → sentence-transformer → OCR向量 (384)
  5. ffmpeg 转 wav → faster-whisper → sentence-transformer → 语音向量 (384)
  6. title → sentence-transformer → 文本向量 (384)
  7. concat(384+768+384+384=1920) → MLP → 512 维输出

依赖安装:
  pip install grpcio grpcio-tools torch torchvision
  pip install git+https://github.com/openai/CLIP.git
  pip install sentence-transformers faster-whisper easyocr
  pip install ultralytics          # YOLOv8，用于人物检测 + attention 抑制
  pip install aiohttp aiofiles Pillow numpy

生成 gRPC 代码:
  python -m grpc_tools.protoc -I. \
      --python_out=. --grpc_python_out=. video_embed.proto
"""

import os
import time
import tempfile
import logging
import subprocess
from pathlib import Path
from concurrent import futures
from typing import List, Tuple

import grpc
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image

# ── 生成的 gRPC 代码 ──────────────────────────────────────────────────────────
import video_embed_pb2
import video_embed_pb2_grpc
import cv2

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# 全局配置
# ─────────────────────────────────────────────────────────────────────────────
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
FRAME_INTERVAL_SEC = 2      # 每 N 秒抽一帧
MAX_FRAMES = 48             # 上限帧数，防止超长视频 OOM
MIN_FRAMES = 4
CLIP_BATCH = 16             # CLIP 推理批大小
OCR_MAX_FRAMES = 12         # OCR 最多处理帧数（较慢，做稀疏采样）
WHISPER_MODEL = "large-v3"  # tiny/base/small/medium/large-v3
TEXT_MODEL = "paraphrase-multilingual-MiniLM-L12-v2"  # 384 维
MLP_WEIGHTS = os.getenv("MLP_WEIGHTS_PATH", "weights/mlp_fusion.pt")

# ─────────────────────────────────────────────────────────────────────────────
# MLP 融合网络
# ─────────────────────────────────────────────────────────────────────────────
class FusionMLP(nn.Module):
    """
    输入: concat(title_vec, clip_vec, ocr_vec, asr_vec)
          = 384 + 768 + 384 + 384 = 1920 维
    输出: 512 维 L2 归一化向量
    """
    def __init__(self, in_dim: int = 1920, out_dim: int = 512):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(in_dim, 1024),
            nn.LayerNorm(1024),
            nn.GELU(),
            nn.Dropout(0.1),
            nn.Linear(1024, out_dim),
            nn.LayerNorm(out_dim),
        )
        self._init_weights()

    def _init_weights(self):
        for m in self.modules():
            if isinstance(m, nn.Linear):
                nn.init.xavier_uniform_(m.weight)
                nn.init.zeros_(m.bias)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return F.normalize(self.net(x), p=2, dim=-1)


# ─────────────────────────────────────────────────────────────────────────────
# 模型单例（服务启动时加载一次）
# ─────────────────────────────────────────────────────────────────────────────
class ModelRegistry:
    """懒加载 + 单例，所有模型只初始化一次。"""

    def __init__(self):
        self._clip_model = None
        self._clip_preprocess = None
        self._text_model = None
        self._whisper_model = None
        self._ocr_reader = None
        self._mlp = None

    # ── CLIP ────────────────────────────────────────────────────────────────
    def clip(self):
        if self._clip_model is None:
            import clip
            log.info(f"加载 CLIP ViT-L/14 on {DEVICE}")
            self._clip_model, self._clip_preprocess = clip.load(
                "ViT-L/14@336px", device=DEVICE, jit=False  # 加上 @336px
            )
            self._clip_model.eval()
            log.info(f"CLIP 就绪，视觉输出维度: {self._clip_model.visual.output_dim}")
        return self._clip_model, self._clip_preprocess

    # ── Sentence-Transformer ────────────────────────────────────────────────
    def text(self):
        if self._text_model is None:
            from sentence_transformers import SentenceTransformer
            log.info(f"加载 SentenceTransformer: {TEXT_MODEL}")
            self._text_model = SentenceTransformer(TEXT_MODEL, device=DEVICE)
        return self._text_model

    # ── faster-whisper ──────────────────────────────────────────────────────
    def whisper(self):
        if self._whisper_model is None:
            from faster_whisper import WhisperModel
            compute = "float16" if DEVICE == "cuda" else "int8"
            log.info(f"加载 faster-whisper [{WHISPER_MODEL}] compute={compute}")
            self._whisper_model = WhisperModel(
                WHISPER_MODEL, device=DEVICE, compute_type=compute
            )
        return self._whisper_model

    # ── EasyOCR ─────────────────────────────────────────────────────────────
    def ocr(self):
        if self._ocr_reader is None:
            import easyocr
            log.info("加载 EasyOCR (ch_sim + en)")
            self._ocr_reader = easyocr.Reader(
                ["ch_sim", "en"], gpu=(DEVICE == "cuda"), verbose=False
            )
        return self._ocr_reader

    # ── MLP ─────────────────────────────────────────────────────────────────
    def mlp(self) -> FusionMLP:
        if self._mlp is None:
            self._mlp = FusionMLP().to(DEVICE).eval()
            p = Path(MLP_WEIGHTS)
            if p.exists():
                state = torch.load(str(p), map_location=DEVICE)
                self._mlp.load_state_dict(state)
                log.info(f"MLP 权重已加载: {p}")
            else:
                log.warning(f"MLP 权重不存在 [{p}]，使用 Xavier 占位初始化并保存")
                p.parent.mkdir(parents=True, exist_ok=True)
                torch.save(self._mlp.state_dict(), str(p))
        return self._mlp


_registry = ModelRegistry()


# ─────────────────────────────────────────────────────────────────────────────
# 工具函数
# ─────────────────────────────────────────────────────────────────────────────
def _run(cmd: List[str], check: bool = True) -> subprocess.CompletedProcess:
    """同步运行 shell 命令，失败时抛出 RuntimeError。"""
    result = subprocess.run(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"命令失败: {' '.join(cmd)}\n{result.stderr.decode()}"
        )
    return result


def download_video(url: str, dest: Path) -> None:
    """用 curl / wget 流式下载视频。"""
    log.info(f"⬇ 下载视频: {url}")
    _run(["curl", "-L", "-o", str(dest), "--max-time", "600", url])
    log.info(f"下载完成: {dest.stat().st_size / 1e6:.1f} MB")


def get_duration(video_path: Path) -> float:
    """ffprobe 获取视频时长（秒）。"""
    r = _run([
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        str(video_path),
    ])
    return float(r.stdout.decode().strip())


def extract_frames(video_path: Path, frames_dir: Path) -> List[Path]:
    """
    动态抽帧：
      - 每 FRAME_INTERVAL_SEC 秒一帧
      - 超过 MAX_FRAMES 则等比稀疏
      - 输出 336x336 居中裁剪（CLIP 推荐尺寸）
    """
    duration = get_duration(video_path)
    interval = FRAME_INTERVAL_SEC
    expected = int(duration / interval)

    if expected > MAX_FRAMES:
        interval = duration / MAX_FRAMES
    elif expected < MIN_FRAMES:
        interval = max(duration / MIN_FRAMES, 0.5)

    log.info(f"视频时长 {duration:.1f}s，抽帧间隔 {interval:.2f}s")
    _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-i", str(video_path),
        "-vf", (
            f"fps=1/{interval:.4f},"
            "scale=336:336:force_original_aspect_ratio=decrease,"
            "pad=336:336:(ow-iw)/2:(oh-ih)/2"
        ),
        "-q:v", "2",
        str(frames_dir / "frame_%04d.jpg"),
    ])
    frames = sorted(frames_dir.glob("frame_*.jpg"))
    log.info(f"抽帧完成: {len(frames)} 帧")
    return frames


def extract_audio(video_path: Path, audio_path: Path) -> bool:
    """转换为 16kHz 单声道 WAV，无音轨返回 False。"""
    r = _run([
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-i", str(video_path),
        "-vn", "-acodec", "pcm_s16le",
        "-ar", "16000", "-ac", "1",
        str(audio_path),
    ], check=False)
    if r.returncode != 0:
        log.warning("视频无音频流，跳过 ASR")
        return False
    log.info(f"音频提取完成: {audio_path.stat().st_size / 1e3:.0f} KB")
    return True


# ─────────────────────────────────────────────────────────────────────────────
# 人物感知 CLIP 编码器
# ─────────────────────────────────────────────────────────────────────────────
class PersonSuppressedCLIP:
    """
    在 CLIP ViT 的最后一个 Transformer block 的自注意力层
    注入 attention bias，把属于"人物"区域的 patch token 权重压低。

    原理：
      ViT-L/14 将 336×336 图像切成 24×24 = 576 个 patch（14px/patch）
      + 1 个 [CLS] token，共 577 个 token。
      自注意力的 Q·K^T softmax 之前加上 bias 矩阵：
        人物 patch 对应列 → 加一个很大的负数（-∞ 方向），
        使 softmax 后该列权重趋近于 0，
        其他 token 就不再"关注"这些人物 patch。

    参数:
      person_suppress_weight : 施加到人物 patch 的 attention bias（负值越大抑制越强）
      person_conf_threshold  : YOLO 检测置信度阈值
      inject_last_n_layers   : 在最后 N 个 Transformer block 注入 bias（越多效果越强）
    """

    PATCH_SIZE = 14          # ViT-L/14 每个 patch 边长（像素）
    CLIP_INPUT = 336         # CLIP 输入图像尺寸
    NUM_PATCHES_SIDE = 336 // 14  # = 24
    NUM_PATCHES = 24 * 24    # = 576
    NUM_TOKENS = 577         # 576 patch + 1 CLS

    def __init__(
        self,
        clip_model,
        clip_preprocess,
        person_suppress_weight: float = -10.0,
        person_conf_threshold: float = 0.4,
        inject_last_n_layers: int = 3,
    ):
        self.model = clip_model
        self.preprocess = clip_preprocess
        self.suppress_w = person_suppress_weight
        self.conf_thresh = person_conf_threshold
        self.n_layers = inject_last_n_layers

        # 懒加载 YOLO
        self._yolo = None
        # 注册 hook 句柄（用于清理）
        self._hooks: List = []
        # 当前 batch 的 attention bias，shape (B, 1, T, T)
        self._attn_bias: torch.Tensor = None

    # ── YOLO 检测 ────────────────────────────────────────────────────────────
    def _get_yolo(self):
        if self._yolo is None:
            from ultralytics import YOLO
            log.info("加载 YOLOv8n（人物检测）")
            self._yolo = YOLO("yolov8n.pt")  # 自动下载，~6MB
        return self._yolo

    def _detect_person_boxes(self, pil_img: Image.Image) -> List[tuple]:
        """
        返回归一化人物框列表 [(x1_n, y1_n, x2_n, y2_n), ...]
        坐标范围 [0, 1]，相对于原图尺寸。
        COCO class 0 = person。
        """
        yolo = self._get_yolo()
        # YOLO 推理（静默模式）
        results = yolo(pil_img, classes=[0], conf=self.conf_thresh, verbose=False)
        boxes = []
        if results and results[0].boxes is not None:
            bxs = results[0].boxes.xyxyn.cpu().numpy()  # 归一化坐标
            for b in bxs:
                boxes.append((float(b[0]), float(b[1]), float(b[2]), float(b[3])))
        return boxes

    # ── Patch → token 索引映射 ───────────────────────────────────────────────
    def _boxes_to_patch_mask(self, boxes: List[tuple]) -> torch.BoolTensor:
        """
        将归一化人物框转换为 patch-level bool mask。
        返回 shape (NUM_TOKENS,)，True 表示该 token 属于人物区域。
        CLS token（index 0）固定为 False。
        """
        S = self.NUM_PATCHES_SIDE  # 24
        mask = torch.zeros(self.NUM_TOKENS, dtype=torch.bool)

        for (x1n, y1n, x2n, y2n) in boxes:
            # 归一化坐标 → patch 坐标（行列）
            col_start = max(0, int(x1n * S))
            col_end   = min(S, int(np.ceil(x2n * S)))
            row_start = max(0, int(y1n * S))
            row_end   = min(S, int(np.ceil(y2n * S)))

            for r in range(row_start, row_end):
                for c in range(col_start, col_end):
                    token_idx = 1 + r * S + c   # +1 跳过 CLS
                    mask[token_idx] = True

        return mask

    def _build_attn_bias(
        self, person_masks: List[torch.BoolTensor]
    ) -> torch.Tensor:
        """
        将 batch 中每张图的 person mask 组装成 attention bias。
        shape: (B, 1, T, T)
        策略：所有 token 在 attend 到人物 patch 列时加负偏置
              → softmax 后人物 patch 被其他 token 忽略
        """
        B = len(person_masks)
        T = self.NUM_TOKENS
        bias = torch.zeros(B, 1, T, T, dtype=torch.float32)

        for b, mask in enumerate(person_masks):
            # mask shape (T,)，True 列加负偏置
            person_cols = mask.nonzero(as_tuple=True)[0]
            if len(person_cols) > 0:
                bias[b, 0, :, person_cols] = self.suppress_w

        return bias  # (B, 1, T, T)

    # ── Hook 注入 ────────────────────────────────────────────────────────────
    def _make_attn_hook(self, layer_idx: int):
        """
        为 ViT ResidualAttentionBlock 的 attn 模块注册 forward pre-hook，
        在 Q·K^T 计算后、softmax 前注入 bias。

        CLIP 的 MultiheadAttention 调用路径：
            block.attn(q, k, v, attn_mask=None)
        我们通过 monkey-patch 临时替换 forward，注入 bias 后还原。
        """
        suppress_w = self.suppress_w

        def hook(module, args, kwargs):
            # args[0] = query, args[1] = key, args[2] = value
            # 向 attn_mask 注入 bias（MHA 支持 additive mask）
            if self._attn_bias is not None:
                B_heads = self._attn_bias.shape[0]
                T = self._attn_bias.shape[2]
                num_heads = module.num_heads

                # MHA 期望 attn_mask: (B*H, T, T) 或 (T, T)
                bias_3d = (
                    self._attn_bias               # (B, 1, T, T)
                    .expand(-1, num_heads, -1, -1)  # (B, H, T, T)
                    .reshape(B_heads * num_heads, T, T)
                    .to(args[0].device)
                )
                # 合并到已有 attn_mask
                existing = kwargs.get("attn_mask", None)
                if existing is not None:
                    kwargs["attn_mask"] = existing + bias_3d
                else:
                    kwargs["attn_mask"] = bias_3d
            return args, kwargs

        return hook

    def _register_hooks(self):
        """在最后 n_layers 个 ResidualAttentionBlock 注册 hook。"""
        transformer = self.model.visual.transformer
        blocks = transformer.resblocks   # ModuleList
        target_blocks = blocks[-self.n_layers:]

        for blk in target_blocks:
            h = blk.attn.register_forward_pre_hook(
                self._make_attn_hook(0), with_kwargs=True
            )
            self._hooks.append(h)

    def _remove_hooks(self):
        for h in self._hooks:
            h.remove()
        self._hooks.clear()

    # ── 主推理接口 ───────────────────────────────────────────────────────────
    def encode_frames(self, frames: List[Path]) -> np.ndarray:
        """
        对所有帧：
          1. YOLO 检测人物 → patch mask
          2. 构建 attention bias
          3. 注入 hook → CLIP 前向 → 移除 hook
          4. Mean Pooling → L2 归一化 → (768,)
        """
        DIM = self.model.visual.output_dim  # 768

        if not frames:
            return np.zeros(DIM, dtype=np.float32)

        all_vecs = []

        for i in range(0, len(frames), CLIP_BATCH):
            batch_paths = frames[i: i + CLIP_BATCH]
            pil_imgs, tensors, person_masks = [], [], []

            for p in batch_paths:
                try:
                    img = Image.open(p).convert("RGB")
                    pil_imgs.append(img)
                    tensors.append(self.preprocess(img))
                except Exception as e:
                    log.warning(f"CLIP 帧跳过 {p}: {e}")

            if not tensors:
                continue

            # ① YOLO 检测（在原始 PIL 图上跑，速度快）
            for img in pil_imgs:
                boxes = self._detect_person_boxes(img)
                person_masks.append(self._boxes_to_patch_mask(boxes))

            n_person_patches = sum(m.sum().item() for m in person_masks)
            log.debug(
                f"批次 {i//CLIP_BATCH}: {len(tensors)} 帧，"
                f"共 {n_person_patches} 个人物 patch 被压制"
            )

            # ② 构建 attention bias
            self._attn_bias = self._build_attn_bias(person_masks).to(DEVICE)

            # ③ 注入 hook → 前向 → 清理
            self._register_hooks()
            try:
                batch_t = torch.stack(tensors).to(DEVICE)
                with torch.no_grad(), torch.amp.autocast('cuda', enabled=(DEVICE == "cuda")):
                    feats = self.model.encode_image(batch_t)
                    feats = F.normalize(feats.float(), dim=-1)
                all_vecs.append(feats.cpu().numpy())
            finally:
                self._remove_hooks()
                self._attn_bias = None

        if not all_vecs:
            return np.zeros(DIM, dtype=np.float32)

        stacked = np.concatenate(all_vecs, axis=0)   # (N, 768)
        mean = stacked.mean(axis=0)
        norm = np.linalg.norm(mean)
        return (mean / norm if norm > 1e-8 else mean).astype(np.float32)


# ─────────────────────────────────────────────────────────────────────────────
# 特征提取函数
# ─────────────────────────────────────────────────────────────────────────────
def get_clip_vector(frames: List[Path]) -> np.ndarray:
    """
    使用 PersonSuppressedCLIP 对帧编码。
    YOLO 检测人物 → attention bias 压制人物 patch → Mean Pooling → (768,)
    """
    clip_model, clip_preprocess = _registry.clip()
    encoder = PersonSuppressedCLIP(
        clip_model=clip_model,
        clip_preprocess=clip_preprocess,
        person_suppress_weight=-10.0,   # 调大绝对值 = 抑制更强
        person_conf_threshold=0.4,      # YOLO 检测置信度阈值
        inject_last_n_layers=3,         # 最后 3 层注入（可调 1~24）
    )
    return encoder.encode_frames(frames)


def get_ocr_vector(frames: List[Path]) -> np.ndarray:
    """
    获取视频帧的 OCR 特征向量
    修复了 EasyOCR 内部 utils.py 对彩色图像解包 img.shape 导致的 ValueError
    """
    reader = _registry.ocr()
    text_model = _registry.text()

    # 1. 均匀采样逻辑
    sampled = frames
    if len(frames) > OCR_MAX_FRAMES:
        step = len(frames) / OCR_MAX_FRAMES
        sampled = [frames[int(i * step)] for i in range(OCR_MAX_FRAMES)]

    seen, texts = set(), []

    # 2. 遍历采样帧进行文字识别
    for p in sampled:
        try:
            # --- 核心修复逻辑 ---
            # 使用 OpenCV 手动读取图片，规避 EasyOCR 内部读取时的兼容性问题
            img = cv2.imread(str(p))
            if img is None:
                log.warning(f"无法读取图片文件: {p}")
                continue

            # 强制转换为灰度图，确保 img.shape 长度固定为 2 (height, width)
            # 这一步彻底解决了 ValueError: too many values to unpack (expected 2)
            gray_img = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

            # 传入处理好的灰度数组，detail=1 获取坐标、文字和置信度
            results = reader.readtext(gray_img, detail=1)

            # 3. 解析识别结果
            for res in results:
                # 兼容性取值：最后一位是置信度，倒数第二位是文本内容
                if len(res) >= 2:
                    text = str(res[-2]).strip()
                    conf = float(res[-1])

                    # 过滤置信度过低或重复的文本
                    if conf >= 0.5 and text and text not in seen:
                        seen.add(text)
                        texts.append(text)

        except Exception as e:
            # 依然保留日志，方便后续万一有其他问题时排查
            log.warning(f"OCR 内部解析异常 {p.name}: {str(e)}")

    # 4. 文本融合与向量化
    # 如果没提取到文字，用占位符防止嵌入模型报错
    merged = " ".join(texts) if texts else "[NO OCR TEXT]"

    # 打印提取结果的缩略信息
    log.info(f"OCR 提取完成 | 去重文字数: {len(texts)} | 内容概览: {merged[:50]}...")

    # 使用 SentenceTransformer (或类似的 text_model) 生成特征向量
    vec = text_model.encode(
        merged,
        normalize_embeddings=True,
        show_progress_bar=False
    )

    return vec.astype(np.float32)

def get_asr_vector(audio_path: Path, has_audio: bool) -> np.ndarray:
    """
    faster-whisper 转录 → sentence-transformer → (384,)
    无音频时返回 [NO AUDIO] 的嵌入
    """
    text_model = _registry.text()

    if not has_audio or not audio_path.exists():
        vec = text_model.encode("[NO AUDIO]", normalize_embeddings=True, show_progress_bar=False)
        return vec.astype(np.float32)

    whisper = _registry.whisper()
    segments, info = whisper.transcribe(
        str(audio_path),
        beam_size=5,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
    )
    transcript = " ".join(seg.text.strip() for seg in segments)
    log.info(
        f"ASR 完成: lang={info.language} ({info.language_probability:.0%}), "
        f"{len(transcript)} 字符"
    )

    text = transcript if transcript.strip() else "[SILENT]"
    vec = text_model.encode(text, normalize_embeddings=True, show_progress_bar=False)
    return vec.astype(np.float32)


def get_title_vector(title: str) -> np.ndarray:
    """title → sentence-transformer → (384,)"""
    model = _registry.text()
    vec = model.encode(
        title if title.strip() else "[NO TITLE]",
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return vec.astype(np.float32)


def fuse_to_512(
    title_vec: np.ndarray,   # 384
    clip_vec: np.ndarray,    # 768
    ocr_vec: np.ndarray,     # 384
    asr_vec: np.ndarray,     # 384
) -> np.ndarray:
    """concat → MLP → 512 维 L2 归一化向量"""
    concat = np.concatenate([title_vec, clip_vec, ocr_vec, asr_vec])  # (1920,)
    tensor = torch.from_numpy(concat).unsqueeze(0).float().to(DEVICE)  # (1, 1920)
    mlp = _registry.mlp()
    with torch.no_grad():
        out = mlp(tensor)   # (1, 512)
    return out.squeeze(0).cpu().numpy().astype(np.float32)


# ─────────────────────────────────────────────────────────────────────────────
# gRPC 服务实现
# ─────────────────────────────────────────────────────────────────────────────
class VideoEmbedServicer(video_embed_pb2_grpc.VideoEmbedServiceServicer):

    def GetVideoEmbedding(self, request, context):
        """
        单一对外方法：
          输入: video_url (str), title (str)
          输出: embedding [512 float], status, message
        """
        t0 = time.time()
        video_url = request.video_url.strip()
        title = request.title.strip()

        log.info(f"收到请求 | url={video_url} | title={title!r}")

        try:
            with tempfile.TemporaryDirectory(prefix="vemb_") as tmp:
                tmp = Path(tmp)
                video_path = tmp / "video.mp4"
                audio_path = tmp / "audio.wav"
                frames_dir = tmp / "frames"
                frames_dir.mkdir()

                # ── Step 1: 下载视频 ─────────────────────────────────────
                download_video(video_url, video_path)

                # ── Step 2: ffmpeg 抽帧 + 提音频 ─────────────────────────
                frames = extract_frames(video_path, frames_dir)
                has_audio = extract_audio(video_path, audio_path)

                # ── Step 3: CLIP 视觉向量 (768) ───────────────────────────
                log.info("Step 3/6: CLIP 视觉特征")
                clip_vec = get_clip_vector(frames)

                # ── Step 4: OCR 文字向量 (384) ────────────────────────────
                log.info("Step 4/6: OCR 文字提取")
                ocr_vec = get_ocr_vector(frames)

                # ── Step 5: ASR 语音向量 (384) ────────────────────────────
                log.info("Step 5/6: Whisper ASR")
                asr_vec = get_asr_vector(audio_path, has_audio)

                # ── Step 6: title 文本向量 (384) ──────────────────────────
                title_vec = get_title_vector(title)

                # ── Step 7: MLP 融合 → 512 ────────────────────────────────
                log.info("Step 6/6: MLP 融合")
                embedding = fuse_to_512(title_vec, clip_vec, ocr_vec, asr_vec)

            elapsed = time.time() - t0
            log.info(f"✅ 完成，耗时 {elapsed:.2f}s，输出维度: {embedding.shape}")

            return video_embed_pb2.VideoEmbedResponse(
                embedding=embedding.tolist(),
                status="ok",
                message=f"耗时 {elapsed:.2f}s | 帧数 {len(frames)} | 音频 {'有' if has_audio else '无'}",
            )

        except Exception as e:
            log.exception("Pipeline 异常")
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return video_embed_pb2.VideoEmbedResponse(
                embedding=[],
                status="error",
                message=str(e),
            )


# ─────────────────────────────────────────────────────────────────────────────
# 启动入口
# ─────────────────────────────────────────────────────────────────────────────
def serve(port: int = 50051, max_workers: int = 4):
    log.info(f"预热模型中（device={DEVICE}）...")
    # 预加载所有模型，避免首次请求延迟
    _registry.clip()
    _registry.text()
    _registry.whisper()
    _registry.ocr()
    _registry.mlp()
    log.info("所有模型就绪")

    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=max_workers),
        options=[
            ("grpc.max_receive_message_length", 64 * 1024 * 1024),
            ("grpc.max_send_message_length",    64 * 1024 * 1024),
        ],
    )
    video_embed_pb2_grpc.add_VideoEmbedServiceServicer_to_server(
        VideoEmbedServicer(), server
    )
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    log.info(f"🚀 gRPC 服务已启动，监听端口 {port}")
    server.wait_for_termination()


if __name__ == "__main__":
    serve()