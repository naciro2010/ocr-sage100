import io
import os
import time
import logging
from contextlib import asynccontextmanager

import fitz  # PyMuPDF
import numpy as np
from PIL import Image
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
from paddleocr import PaddleOCR

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ocr-service")

_ocr_instances: dict[str, PaddleOCR] = {}

DPI = int(os.getenv("OCR_DPI", "300"))


def get_ocr(lang: str = "fr") -> PaddleOCR:
    if lang not in _ocr_instances:
        logger.info("Initializing PaddleOCR for lang=%s", lang)
        _ocr_instances[lang] = PaddleOCR(
            use_angle_cls=True,
            lang=lang,
            show_log=False,
            use_gpu=False,
        )
        logger.info("PaddleOCR ready for lang=%s", lang)
    return _ocr_instances[lang]


@asynccontextmanager
async def lifespan(application: FastAPI):
    logger.info("Warming up PaddleOCR (fr)...")
    get_ocr("fr")
    logger.info("Warmup complete")
    yield


app = FastAPI(title="OCR Service", version="2.0.0", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", "engine": "paddleocr", "version": "3.4.0"}


@app.post("/ocr")
async def ocr_file(
    file: UploadFile = File(...),
    lang: str = "fr",
):
    if lang not in ("fr", "ar", "en", "latin"):
        lang = "fr"

    start = time.time()
    content = await file.read()
    filename = file.filename or "unknown"

    try:
        if filename.lower().endswith(".pdf"):
            pages_data = ocr_pdf(content, lang)
        else:
            pages_data = ocr_image(content, lang)
    except Exception as e:
        logger.error("OCR failed for %s: %s", filename, str(e))
        raise HTTPException(status_code=500, detail=f"OCR failed: {str(e)}")

    full_text = "\n\n".join(page["text"] for page in pages_data)
    duration_ms = int((time.time() - start) * 1000)

    logger.info(
        "OCR complete: %s, %d pages, %d chars, %dms",
        filename, len(pages_data), len(full_text), duration_ms,
    )

    return JSONResponse({
        "text": full_text,
        "pages": pages_data,
        "page_count": len(pages_data),
        "engine": "paddleocr",
        "duration_ms": duration_ms,
    })


@app.post("/ocr/batch")
async def ocr_batch(
    files: list[UploadFile] = File(...),
    lang: str = "fr",
):
    results = []
    for f in files:
        try:
            content = await f.read()
            filename = f.filename or "unknown"
            if filename.lower().endswith(".pdf"):
                pages_data = ocr_pdf(content, lang)
            else:
                pages_data = ocr_image(content, lang)
            full_text = "\n\n".join(page["text"] for page in pages_data)
            results.append({
                "filename": filename,
                "text": full_text,
                "page_count": len(pages_data),
                "success": True,
            })
        except Exception as e:
            results.append({
                "filename": f.filename,
                "text": "",
                "page_count": 0,
                "success": False,
                "error": str(e),
            })
    return JSONResponse({"results": results})


def ocr_pdf(content: bytes, lang: str) -> list[dict]:
    doc = fitz.open(stream=content, filetype="pdf")
    pages = []

    for page_num in range(len(doc)):
        page = doc[page_num]
        zoom = DPI / 72.0
        mat = fitz.Matrix(zoom, zoom)
        pix = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)

        img_array = np.frombuffer(pix.samples, dtype=np.uint8).reshape(
            pix.height, pix.width, 3
        )

        page_data = ocr_array(img_array, lang)
        pages.append(page_data)

    doc.close()
    return pages


def ocr_image(content: bytes, lang: str) -> list[dict]:
    img = Image.open(io.BytesIO(content)).convert("RGB")
    img_array = np.array(img)
    return [ocr_array(img_array, lang)]


def ocr_array(img_array: np.ndarray, lang: str) -> dict:
    ocr = get_ocr(lang)
    result = ocr.ocr(img_array, cls=True)

    lines = []
    text_parts = []

    if result and result[0]:
        for line in result[0]:
            bbox = line[0]
            text = line[1][0]
            confidence = float(line[1][1])

            lines.append({
                "text": text,
                "confidence": round(confidence, 4),
                "bbox": [[int(p[0]), int(p[1])] for p in bbox],
            })
            text_parts.append(text)

    page_text = "\n".join(text_parts)
    avg_confidence = (
        sum(ln["confidence"] for ln in lines) / len(lines) if lines else 0.0
    )

    return {
        "text": page_text,
        "lines": lines,
        "line_count": len(lines),
        "avg_confidence": round(avg_confidence, 4),
    }
