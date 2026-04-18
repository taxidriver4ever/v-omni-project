import onnxruntime as ort
session = ort.InferenceSession("D:/private_project/v-omni-project/v-omni-ai/v-omni-embedding/model/resnet50-v1-12.onnx")
print("输出形状:", session.get_outputs()[0].shape)