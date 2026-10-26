FROM python:3.12-slim
WORKDIR /app
COPY pipeline/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home wanlu && mkdir /data && chown wanlu /data
COPY pipeline/minutes.py pipeline/cloud_server.py /app/pipeline/
COPY pipeline/mobile /app/pipeline/mobile
USER wanlu
ENV WANLU_BIND=0.0.0.0 WANLU_DATA=/data PORT=8734 PYTHONUNBUFFERED=1
EXPOSE 8734
CMD ["python", "pipeline/cloud_server.py"]
