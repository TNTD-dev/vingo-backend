"""Private PDF storage and extraction. Accessible only by the backend."""
import concurrent.futures
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path

import boto3
from fastapi import FastAPI, Request, HTTPException, Depends
from fastapi.responses import Response
from botocore.exceptions import ClientError

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
pool = concurrent.futures.ThreadPoolExecutor(max_workers=2)
# Uvicorn runs one process. Serialize object writes and deletes across its threads.
object_lock = threading.Lock()
bucket = 'venue-rules'
s3 = boto3.client('s3', endpoint_url=os.environ['S3_ENDPOINT'],
                  aws_access_key_id=os.environ['S3_ACCESS_KEY'], aws_secret_access_key=os.environ['S3_SECRET_KEY'])

def auth(request: Request):
    if request.headers.get('X-Internal-Key') != os.environ['INTERNAL_KEY']:
        raise HTTPException(403)

def key(raw):
    try: return str(uuid.UUID(raw))
    except ValueError: raise HTTPException(400, 'INVALID_ID')

def put_status(doc_id, data):
    s3.put_object(Bucket=bucket, Key=doc_id+'.json', Body=json.dumps(data).encode(), ContentType='application/json')

def put_extraction_status(doc_id, data):
    with object_lock:
        try:
            current = json.loads(s3.get_object(Bucket=bucket, Key=doc_id+'.json')['Body'].read())
        except ClientError as error:
            if error.response['Error']['Code'] in ('NoSuchKey', '404'):
                return False
            raise
        if current.get('generation') != data.get('generation'):
            return False
        put_status(doc_id, data)
        return True

def run(command, timeout=60):
    return subprocess.run(command, capture_output=True, timeout=timeout, check=True).stdout

def extract(doc_id, data):
    try:
        data['extraction_status'] = 'PROCESSING'
        if not put_extraction_status(doc_id, data):
            return
        body = s3.get_object(Bucket=bucket, Key=doc_id+'.pdf')['Body'].read()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'input.pdf'
            path.write_bytes(body)
            text = run(['pdftotext', '-enc', 'UTF-8', str(path), '-']).decode('utf-8')
            status = 'EXTRACTED'
            if len(''.join(text.split())) < 100:
                texts = []
                for page in range(1, data['page_count']+1):
                    output = str(Path(temporary)/'page')
                    run(['pdftoppm', '-f', str(page), '-l', str(page), '-scale-to', '2200', '-png', '-singlefile', str(path), output])
                    texts.append(run(['tesseract', output+'.png', 'stdout', '-l', 'vie+eng'], timeout=90).decode('utf-8'))
                    if sum(map(len, texts)) > 250000: break
                text = '\n'.join(texts)
                if not text.strip(): raise RuntimeError('OCR_FAILED')
                status = 'OCR_COMPLETED'
            data.update(extraction_status=status, extracted_text=text[:250000], extracted_text_truncated=len(text)>250000)
    except Exception:
        data.update(extraction_status='OCR_FAILED', error_code='OCR_FAILED')
    put_extraction_status(doc_id, data)

@app.on_event('startup')
def startup():
    for attempt in range(30):
        try:
            if bucket not in [b['Name'] for b in s3.list_buckets()['Buckets']]: s3.create_bucket(Bucket=bucket)
            # A private bucket is the default; never configure a public policy.
            for page in s3.get_paginator('list_objects_v2').paginate(Bucket=bucket):
                for item in page.get('Contents', []):
                    if not item['Key'].endswith('.json'): continue
                    data = json.loads(s3.get_object(Bucket=bucket, Key=item['Key'])['Body'].read())
                    if data.get('extraction_status') in ('QUEUED', 'PROCESSING'):
                        pool.submit(extract, item['Key'][:-5], data)
            return
        except Exception:
            if attempt == 29: raise
            time.sleep(1)

@app.put('/documents/{doc_id}', dependencies=[Depends(auth)])
async def upload(doc_id: str, request: Request):
    doc_id = key(doc_id)
    body = bytearray()
    async for part in request.stream():
        body.extend(part)
        if len(body)>20*1024*1024: raise HTTPException(413, 'PDF_TOO_LARGE')
    if request.headers.get('content-type','').split(';')[0] != 'application/pdf' or not body.startswith(b'%PDF-'):
        raise HTTPException(415, 'INVALID_PDF')
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary)/'input.pdf'; path.write_bytes(body)
        try: info = run(['pdfinfo', str(path)], timeout=20).decode('utf-8')
        except Exception: raise HTTPException(422, 'PDF_PASSWORD_PROTECTED_OR_INVALID')
        if re.search(r'Encrypted:\s+yes', info): raise HTTPException(422, 'PDF_PASSWORD_PROTECTED')
        pages = int(re.search(r'Pages:\s+(\d+)', info).group(1))
        if not 1<=pages<=100: raise HTTPException(422, 'PDF_PAGE_LIMIT')
    data = dict(page_count=pages, size_bytes=len(body), sha256=hashlib.sha256(body).hexdigest(), extraction_status='QUEUED', extracted_text_truncated=False)
    with object_lock:
        s3.put_object(Bucket=bucket, Key=doc_id+'.pdf', Body=bytes(body), ContentType='application/pdf')
        queued = dict(data, generation=uuid.uuid4().hex)
        put_status(doc_id, queued)
    pool.submit(extract, doc_id, queued)
    return data

@app.get('/documents/{doc_id}/status', dependencies=[Depends(auth)])
def status(doc_id: str):
    try:
        data = json.loads(s3.get_object(Bucket=bucket,Key=key(doc_id)+'.json')['Body'].read())
        data.pop('generation', None)
        return data
    except Exception: raise HTTPException(404)

@app.get('/documents/{doc_id}/file', dependencies=[Depends(auth)])
def download(doc_id: str):
    try: return Response(s3.get_object(Bucket=bucket,Key=key(doc_id)+'.pdf')['Body'].read(),media_type='application/pdf')
    except Exception: raise HTTPException(404)

@app.delete('/documents/{doc_id}', dependencies=[Depends(auth)])
def delete(doc_id: str):
    doc_id=key(doc_id)
    with object_lock:
        result = s3.delete_objects(Bucket=bucket, Delete={'Objects':[{'Key':doc_id+'.pdf'},{'Key':doc_id+'.json'}]})
        if result.get('Errors'):
            raise HTTPException(503, 'DOCUMENT_DELETE_FAILED')
    return Response(status_code=204)
