FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends poppler-utils tesseract-ocr tesseract-ocr-vie tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir fastapi==0.115.12 uvicorn==0.34.2 boto3==1.38.23
WORKDIR /app
COPY server.py .
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8091"]
