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
            use_textline_orientation=True,
            lang=lang,
            device="cpu",
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
    loaded_langs = list(_ocr_instances.keys())
    return {
        "status": "ok",
        "engine": "paddleocr",
        "version": "3.4.0",
        "dpi": DPI,
        "loaded_languages": loaded_langs,
        "available_languages": ["fr", "ar", "en", "latin"],
    }


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

    # PaddleOCR 3.x: use predict() instead of ocr()
    try:
        result = ocr.predict(img_array)
    except TypeError:
        # Fallback for older API
        try:
            result = ocr.ocr(img_array)
        except TypeError:
            result = ocr.ocr(img_array, cls=False)

    lines = []
    text_parts = []

    if not result:
        return {"text": "", "lines": [], "line_count": 0, "avg_confidence": 0.0}

    # PaddleOCR 3.x returns dict with 'rec_texts', 'rec_scores', 'dt_polys'
    if isinstance(result, dict):
        texts = result.get("rec_texts", [])
        scores = result.get("rec_scores", [])
        polys = result.get("dt_polys", [])
        for i, text in enumerate(texts):
            conf = float(scores[i]) if i < len(scores) else 0.0
            bbox = polys[i].tolist() if i < len(polys) else []
            lines.append({"text": text, "confidence": round(conf, 4), "bbox": bbox})
            text_parts.append(text)
    # PaddleOCR 3.x can also return list of dicts (one per image)
    elif isinstance(result, list) and len(result) > 0:
        item = result[0] if isinstance(result[0], dict) else None
        if item and "rec_texts" in item:
            texts = item.get("rec_texts", [])
            scores = item.get("rec_scores", [])
            polys = item.get("dt_polys", [])
            for i, text in enumerate(texts):
                conf = float(scores[i]) if i < len(scores) else 0.0
                bbox = polys[i].tolist() if i < len(polys) else []
                lines.append({"text": text, "confidence": round(conf, 4), "bbox": bbox})
                text_parts.append(text)
        # Legacy format: list of [bbox, (text, confidence)]
        elif isinstance(result[0], list):
            for line in result[0]:
                if isinstance(line, list) and len(line) >= 2:
                    bbox = line[0]
                    text = line[1][0] if isinstance(line[1], (list, tuple)) else str(line[1])
                    confidence = float(line[1][1]) if isinstance(line[1], (list, tuple)) and len(line[1]) > 1 else 0.0
                    lines.append({
                        "text": text,
                        "confidence": round(confidence, 4),
                        "bbox": [[int(p[0]), int(p[1])] for p in bbox] if bbox else [],
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
