/**
 * Builds a minimal but structurally valid PDF.
 *
 * The backend rejects uploads whose content type or extension is wrong, and
 * then checks the `%PDF-` magic bytes before accepting. A real PDF keeps the
 * async processing path (PDFBox text extraction) realistic instead of forcing
 * the document into FAILED.
 */
function buildPdf(pageTexts: string[]): Buffer {
  const escape = (text: string) =>
    text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');

  const pageCount = pageTexts.length;
  const fontNum = 3 + 2 * pageCount;
  const objects: string[] = new Array(fontNum);
  objects[0] = '<< /Type /Catalog /Pages 2 0 R >>';
  objects[1] =
    `<< /Type /Pages /Kids [${pageTexts.map((_, index) => `${3 + 2 * index} 0 R`).join(' ')}] ` +
    `/Count ${pageCount} >>`;
  pageTexts.forEach((text, index) => {
    const pageNum = 3 + 2 * index;
    const contentNum = pageNum + 1;
    objects[pageNum - 1] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ` +
      `/Resources << /Font << /F1 ${fontNum} 0 R >> >> /Contents ${contentNum} 0 R >>`;
    const stream = `BT /F1 18 Tf 72 720 Td (${escape(text)}) Tj ET`;
    objects[contentNum - 1] =
      `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`;
  });
  objects[fontNum - 1] = '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>';

  const chunks: Buffer[] = [];
  let offset = 0;
  const push = (value: string) => {
    const bytes = Buffer.from(value, 'latin1');
    chunks.push(bytes);
    offset += bytes.length;
  };

  push('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');

  const offsets: number[] = [];
  objects.forEach((object, index) => {
    offsets.push(offset);
    push(`${index + 1} 0 obj\n${object}\nendobj\n`);
  });

  const xrefOffset = offset;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const objectOffset of offsets) {
    xref += `${String(objectOffset).padStart(10, '0')} 00000 n \n`;
  }
  push(xref);
  push(
    `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`,
  );

  return Buffer.concat(chunks);
}

export function createPdf(text = 'VerityAI end-to-end test document'): Buffer {
  return buildPdf([text]);
}

/**
 * A deliberately long PDF, for tests that need the document to still be
 * processing when the next page opens.
 */
export function createLongPdf(pageCount: number, text = 'VerityAI end-to-end test document'): Buffer {
  return buildPdf(Array.from({ length: pageCount }, (_, index) => `Page ${index + 1}. ${text}`));
}

/** A payload that looks like a PDF to the browser but fails the server gate. */
export function createFakePdf(): Buffer {
  return Buffer.from('This is not a PDF document.\n', 'utf8');
}
