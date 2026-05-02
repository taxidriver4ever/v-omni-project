"""
PersonSuppressedCLIP：在 CLIP ViT 的最后若干 Transformer block 中
注入 attention bias，将人物区域 patch 的权重压低，
让视觉特征更关注视频内容本身（场景、物体、文字等）。

原理：
  ViT-L/14@336px 将 336×336 图像切成 24×24=576 个 patch（每 patch 14px）
  + 1 个 [CLS] token，共 577 个 token。
  在 Q·K^T softmax 之前，向人物 patch 对应的"列"施加一个大负数 bias，
  使 softmax 后该列权重趋近于 0，其他 token 不再"关注"这些人物 patch。
"""
import logging
from pathlib import Path
from typing import List

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from config import DEVICE, CLIP_BATCH

log = logging.getLogger(__name__)


class PersonSuppressedCLIP:
    PATCH_SIZE = 14
    CLIP_INPUT = 336
    NUM_PATCHES_SIDE = 336 // 14   # 24
    NUM_PATCHES = 24 * 24          # 576
    NUM_TOKENS = 577               # 576 patch + 1 CLS

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

        self._yolo = None
        self._hooks: List = []
        self._attn_bias: torch.Tensor | None = None

    # ── YOLO 人物检测 ─────────────────────────────────────────────────────────
    def _get_yolo(self):
        if self._yolo is None:
            from ultralytics import YOLO
            log.info("加载 YOLOv8n（人物检测）")
            self._yolo = YOLO("yolov8n.pt")
        return self._yolo

    def _detect_person_boxes(self, pil_img: Image.Image) -> List[tuple]:
        """返回归一化人物框 [(x1n, y1n, x2n, y2n), ...]，坐标范围 [0,1]。"""
        yolo = self._get_yolo()
        results = yolo(pil_img, classes=[0], conf=self.conf_thresh, verbose=False)
        boxes = []
        if results and results[0].boxes is not None:
            for b in results[0].boxes.xyxyn.cpu().numpy():
                boxes.append((float(b[0]), float(b[1]), float(b[2]), float(b[3])))
        return boxes

    # ── Patch mask 构建 ───────────────────────────────────────────────────────
    def _boxes_to_patch_mask(self, boxes: List[tuple]) -> torch.BoolTensor:
        """归一化框 → patch-level bool mask，shape (NUM_TOKENS,)。"""
        S = self.NUM_PATCHES_SIDE  # 24
        mask = torch.zeros(self.NUM_TOKENS, dtype=torch.bool)
        for (x1n, y1n, x2n, y2n) in boxes:
            col_s = max(0, int(x1n * S))
            col_e = min(S, int(np.ceil(x2n * S)))
            row_s = max(0, int(y1n * S))
            row_e = min(S, int(np.ceil(y2n * S)))
            for r in range(row_s, row_e):
                for c in range(col_s, col_e):
                    mask[1 + r * S + c] = True  # +1 跳过 CLS
        return mask

    def _build_attn_bias(self, person_masks: List[torch.BoolTensor]) -> torch.Tensor:
        """将 batch 的 person mask 组装成 attention bias，shape (B,1,T,T)。"""
        B, T = len(person_masks), self.NUM_TOKENS
        bias = torch.zeros(B, 1, T, T, dtype=torch.float32)
        for b, mask in enumerate(person_masks):
            cols = mask.nonzero(as_tuple=True)[0]
            if len(cols) > 0:
                bias[b, 0, :, cols] = self.suppress_w
        return bias

    # ── Hook 注入 ─────────────────────────────────────────────────────────────
    def _make_attn_hook(self):
        def hook(module, args, kwargs):
            if self._attn_bias is not None:
                num_heads = module.num_heads
                B = self._attn_bias.shape[0]
                T = self._attn_bias.shape[2]
                bias_3d = (
                    self._attn_bias
                    .expand(-1, num_heads, -1, -1)       # (B, H, T, T)
                    .reshape(B * num_heads, T, T)
                    .to(args[0].device)
                )
                existing = kwargs.get("attn_mask", None)
                kwargs["attn_mask"] = (existing + bias_3d) if existing is not None else bias_3d
            return args, kwargs
        return hook

    def _register_hooks(self):
        blocks = self.model.visual.transformer.resblocks
        for blk in blocks[-self.n_layers:]:
            h = blk.attn.register_forward_pre_hook(
                self._make_attn_hook(), with_kwargs=True
            )
            self._hooks.append(h)

    def _remove_hooks(self):
        for h in self._hooks:
            h.remove()
        self._hooks.clear()

    # ── 主接口 ────────────────────────────────────────────────────────────────
    def encode_frames(self, frames: List[Path]) -> np.ndarray:
        """
        对所有帧执行：
          YOLO 检测 → patch mask → attention bias → hook 注入 → CLIP 前向
          → Mean Pooling → L2 归一化 → (768,)
        """
        DIM = self.model.visual.output_dim
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

            for img in pil_imgs:
                boxes = self._detect_person_boxes(img)
                person_masks.append(self._boxes_to_patch_mask(boxes))

            n_suppressed = sum(m.sum().item() for m in person_masks)
            log.debug(f"批次 {i//CLIP_BATCH}: {len(tensors)} 帧，{n_suppressed} 个人物 patch 被压制")

            self._attn_bias = self._build_attn_bias(person_masks).to(DEVICE)
            self._register_hooks()
            try:
                batch_t = torch.stack(tensors).to(DEVICE)
                with torch.no_grad(), torch.amp.autocast("cuda", enabled=(DEVICE == "cuda")):
                    feats = self.model.encode_image(batch_t)
                    feats = F.normalize(feats.float(), dim=-1)
                all_vecs.append(feats.cpu().numpy())
            finally:
                self._remove_hooks()
                self._attn_bias = None

        if not all_vecs:
            return np.zeros(DIM, dtype=np.float32)

        stacked = np.concatenate(all_vecs, axis=0)  # (N, 768)
        mean = stacked.mean(axis=0)
        norm = np.linalg.norm(mean)
        return (mean / norm if norm > 1e-8 else mean).astype(np.float32)
