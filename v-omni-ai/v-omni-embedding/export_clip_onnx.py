import argparse
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
from PIL import Image
from transformers import CLIPModel, CLIPProcessor


def resolve_torch_device(device: str) -> torch.device:
    if device == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(device)


def resolve_onnx_providers(device: str) -> List[str]:
    available = ort.get_available_providers()
    if device in ("auto", "cuda") and "CUDAExecutionProvider" in available:
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return ["CPUExecutionProvider"]


def l2_normalize(x: torch.Tensor) -> torch.Tensor:
    return x / x.norm(dim=-1, keepdim=True).clamp_min(1e-12)


class ClipMeanPoolEncoder(nn.Module):
    """
    双模态编码器：
    - pixel_values: [N, 3, H, W] -> image_embedding: [D] (normalize + mean pool)
    - input_ids: [N, T] -> text_embedding: [N, D] (normalize)
    """

    def __init__(self, clip_model_name: str = "openai/clip-vit-base-patch32", normalize: bool = True):
        super().__init__()
        # Use eager attention to avoid SDPA tracing issues during ONNX export.
        self.clip = CLIPModel.from_pretrained(clip_model_name, attn_implementation="eager")
        self.normalize = normalize

    def forward(
        self,
        pixel_values: Optional[torch.Tensor] = None,
        input_ids: Optional[torch.Tensor] = None,
        attention_mask: Optional[torch.Tensor] = None,
    ) -> Tuple[torch.Tensor, torch.Tensor]:
        if pixel_values is None and input_ids is None:
            raise ValueError("pixel_values and input_ids cannot both be None.")

        if pixel_values is None:
            image_embedding = torch.zeros(
                self.clip.projection_dim,
                dtype=torch.float32,
                device=next(self.parameters()).device,
            )
        else:
            image_features = self.clip.get_image_features(pixel_values=pixel_values)
            if isinstance(image_features, torch.Tensor):
                image_embeds = image_features
            elif hasattr(image_features, "image_embeds"):
                image_embeds = image_features.image_embeds  # type: ignore[attr-defined]
            elif hasattr(image_features, "pooler_output"):
                image_embeds = image_features.pooler_output  # type: ignore[attr-defined]
            else:
                raise TypeError(f"Unsupported image feature output type: {type(image_features)}")
            image_embeds = l2_normalize(image_embeds) if self.normalize else image_embeds
            image_embedding = image_embeds.mean(dim=0)  # [D]

        if input_ids is None:
            text_embedding = torch.zeros(
                1,
                self.clip.projection_dim,
                dtype=torch.float32,
                device=next(self.parameters()).device,
            )
        else:
            text_features = self.clip.get_text_features(input_ids=input_ids, attention_mask=attention_mask)
            if isinstance(text_features, torch.Tensor):
                text_embeds = text_features
            elif hasattr(text_features, "text_embeds"):
                text_embeds = text_features.text_embeds  # type: ignore[attr-defined]
            elif hasattr(text_features, "pooler_output"):
                text_embeds = text_features.pooler_output  # type: ignore[attr-defined]
            else:
                raise TypeError(f"Unsupported text feature output type: {type(text_features)}")
            text_embedding = l2_normalize(text_embeds) if self.normalize else text_embeds  # [N, D]

        return image_embedding, text_embedding


def load_images(image_paths: List[str]) -> List[Image.Image]:
    images: List[Image.Image] = []
    for p in image_paths:
        images.append(Image.open(p).convert("RGB"))
    return images


def preprocess_images(image_paths: List[str], processor: CLIPProcessor) -> torch.Tensor:
    images = load_images(image_paths)
    encoded = processor(images=images, return_tensors="pt")
    return encoded["pixel_values"]  # [N, 3, H, W]


def preprocess_texts(texts: List[str], processor: CLIPProcessor) -> Tuple[torch.Tensor, torch.Tensor]:
    encoded = processor(text=texts, return_tensors="pt", padding=True, truncation=True)
    return encoded["input_ids"], encoded["attention_mask"]  # [N, T], [N, T]


def print_onnx_io_info(onnx_path: str) -> None:
    model = onnx.load(onnx_path)
    input_names = [x.name for x in model.graph.input]
    output_names = [x.name for x in model.graph.output]
    print(f"[ONNX] inputs: {input_names}")
    print(f"[ONNX] outputs: {output_names}")


def export_onnx(
    output_onnx: str,
    clip_model_name: str = "openai/clip-vit-base-patch32",
    opset: int = 17,
) -> None:
    out_path = Path(output_onnx)
    if out_path.exists():
        out_path.unlink()
        print(f"[Clean] removed old file: {output_onnx}")

    model = ClipMeanPoolEncoder(clip_model_name=clip_model_name)
    model.eval()

    dummy_pixel_values = torch.randn(2, 3, 224, 224, dtype=torch.float32)
    dummy_input_ids = torch.ones(2, 77, dtype=torch.int64)
    dummy_attention_mask = torch.ones(2, 77, dtype=torch.int64)

    torch.onnx.export(
        model,
        args=(dummy_pixel_values, dummy_input_ids, dummy_attention_mask),
        f=output_onnx,
        input_names=["pixel_values", "input_ids", "attention_mask"],
        output_names=["image_embedding", "text_embedding"],
        dynamic_axes={
            "pixel_values": {0: "image_batch"},
            "input_ids": {0: "text_batch", 1: "text_seq_len"},
            "attention_mask": {0: "text_batch", 1: "text_seq_len"},
            "image_embedding": {0: "embed_dim"},
            "text_embedding": {0: "text_batch", 1: "embed_dim"},
        },
        opset_version=opset,
        do_constant_folding=True,
        dynamo=False,
    )
    print_onnx_io_info(output_onnx)


def run_pytorch_inference(
    image_paths: List[str],
    clip_model_name: str = "openai/clip-vit-base-patch32",
    device: str = "auto",
) -> np.ndarray:
    torch_device = resolve_torch_device(device)
    print(f"[PyTorch] using device: {torch_device}")
    processor = CLIPProcessor.from_pretrained(clip_model_name)
    model = ClipMeanPoolEncoder(clip_model_name=clip_model_name).to(torch_device)
    model.eval()

    with torch.no_grad():
        pixel_values = preprocess_images(image_paths, processor).to(torch_device)
        image_vec, _ = model(pixel_values=pixel_values, input_ids=None)
    return image_vec.cpu().numpy()


def run_onnx_inference(
    image_paths: List[str],
    onnx_path: str,
    clip_model_name: str = "openai/clip-vit-base-patch32",
    device: str = "auto",
) -> np.ndarray:
    processor = CLIPProcessor.from_pretrained(clip_model_name)
    pixel_values = preprocess_images(image_paths, processor).cpu().numpy().astype(np.float32)

    # 为保持双输入图稳定，这里提供一个最小文本占位输入
    placeholder_input_ids = np.array([[49406, 49407]], dtype=np.int64)
    placeholder_attention_mask = np.array([[1, 1]], dtype=np.int64)

    providers = resolve_onnx_providers(device)
    print(f"[ONNXRuntime] providers: {providers}")
    session = ort.InferenceSession(onnx_path, providers=providers)
    outputs = session.run(
        ["image_embedding"],
        {
            "pixel_values": pixel_values,
            "input_ids": placeholder_input_ids,
            "attention_mask": placeholder_attention_mask,
        },
    )
    return outputs[0]  # [D]


def run_pytorch_text_inference(
    texts: List[str],
    clip_model_name: str = "openai/clip-vit-base-patch32",
    device: str = "auto",
) -> np.ndarray:
    torch_device = resolve_torch_device(device)
    processor = CLIPProcessor.from_pretrained(clip_model_name)
    model = ClipMeanPoolEncoder(clip_model_name=clip_model_name).to(torch_device)
    model.eval()

    with torch.no_grad():
        input_ids, attention_mask = preprocess_texts(texts, processor)
        input_ids = input_ids.to(torch_device)
        attention_mask = attention_mask.to(torch_device)
        _, text_vec = model(pixel_values=None, input_ids=input_ids, attention_mask=attention_mask)
    return text_vec.cpu().numpy()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出集成图片+文本特征提取的 CLIP ONNX 模型")
    parser.add_argument("--images", nargs="+", required=True, help="输入图片路径，支持多张")
    parser.add_argument(
        "--texts",
        nargs="+",
        default=["test title"],
        help="用于文本推理校验的文本（可多条）",
    )
    parser.add_argument(
        "--output-onnx",
        default="clip_mean_pool.onnx",
        help="导出的ONNX文件路径",
    )
    parser.add_argument(
        "--clip-model",
        default="openai/clip-vit-base-patch32",
        help="HuggingFace CLIP 模型名",
    )
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset 版本")
    parser.add_argument(
        "--device",
        choices=["auto", "cuda", "cpu"],
        default="auto",
        help="推理设备，auto 会优先使用 GPU",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    for p in args.images:
        if not Path(p).exists():
            raise FileNotFoundError(f"图片不存在: {p}")

    export_onnx(
        output_onnx=args.output_onnx,
        clip_model_name=args.clip_model,
        opset=args.opset,
    )
    print(f"[OK] ONNX 已导出: {args.output_onnx}")

    pt_image_vec = run_pytorch_inference(
        args.images,
        clip_model_name=args.clip_model,
        device=args.device,
    )
    print(f"[PyTorch] image vector shape: {pt_image_vec.shape}")
    if pt_image_vec.shape != (512,):
        raise RuntimeError(f"Image vector dim mismatch, expected (512,), got {pt_image_vec.shape}")

    onnx_image_vec = run_onnx_inference(
        args.images,
        onnx_path=args.output_onnx,
        clip_model_name=args.clip_model,
        device=args.device,
    )
    print(f"[ONNX] image vector shape: {onnx_image_vec.shape}")
    l2_diff = np.linalg.norm(pt_image_vec - onnx_image_vec)
    print(f"[Check] L2 diff (PyTorch vs ONNX image): {l2_diff:.6f}")

    pt_text_vec = run_pytorch_text_inference(
        args.texts,
        clip_model_name=args.clip_model,
        device=args.device,
    )
    print(f"[PyTorch] text vector shape: {pt_text_vec.shape}")
    if pt_text_vec.ndim != 2 or pt_text_vec.shape[-1] != 512:
        raise RuntimeError(f"Text vector dim mismatch, expected [N,512], got {pt_text_vec.shape}")
    print("[Check] text vector dim is 512.")


if __name__ == "__main__":
    main()
