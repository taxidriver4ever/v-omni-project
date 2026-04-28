## 依赖安装
pip install grpcio grpcio-tools
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
pip install git+https://github.com/openai/CLIP.git
pip install sentence-transformers faster-whisper easyocr
pip install Pillow numpy

## 生成 gRPC 代码（必须先执行）
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. video_embed.proto

## 启动服务端
python server.py

## 调用客户端
python client.py
