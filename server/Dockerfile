FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY dpmb_server ./dpmb_server
RUN pip install --no-cache-dir . \
    && useradd --system --no-create-home dpmb

USER dpmb
ENV PORT=8080
EXPOSE 8080
# Jízdní řád se načítá při startu (~10 s), proto delší start-period.
HEALTHCHECK --interval=60s --timeout=5s --start-period=60s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/health').status==200 else 1)"
CMD ["dpmb-server"]
