from __future__ import annotations

import timm
import torch
from torch import nn
from torchvision import transforms as T

# Small, fast, and exports cleanly to ONNX for mobile. Alternatives that also work:
# "mobilenetv3_large_100" (lighter), "efficientnet_b2" / "tf_efficientnetv2_b0" (heavier, more accurate).
DEFAULT_MODEL = "efficientnet_b0"


def create_model(name: str, num_classes: int, pretrained: bool = True, drop_rate: float = 0.3) -> nn.Module:
    return timm.create_model(name, pretrained=pretrained, num_classes=num_classes, drop_rate=drop_rate)


def norm_stats(model: nn.Module) -> tuple[list[float], list[float]]:
    cfg = timm.data.resolve_data_config({}, model=model)
    return list(cfg["mean"]), list(cfg["std"])


def train_transform(size: int, mean, std):
    return T.Compose([
        T.RandomResizedCrop(size, scale=(0.25, 1.0), ratio=(0.6, 1.66)),
        T.RandomHorizontalFlip(),
        T.TrivialAugmentWide(),
        T.ToTensor(),
        T.Normalize(mean, std),
        T.RandomErasing(p=0.25),
    ])


def eval_transform(size: int, mean, std):
    # Same geometry as the apps: scale the short side to `size`, then take the centre square.
    return T.Compose([T.Resize(size), T.CenterCrop(size), T.ToTensor(), T.Normalize(mean, std)])


class MobileWrapper(nn.Module):
    """What ships to the phone: RGB float [0,1] NCHW in, class probabilities out.

    Baking normalisation and softmax into the graph keeps the app code trivial
    and identical between Android and iOS.
    """

    def __init__(self, model: nn.Module, mean, std):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor(mean).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor(std).view(1, 3, 1, 1))

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return torch.softmax(self.model((image - self.mean) / self.std), dim=1)
