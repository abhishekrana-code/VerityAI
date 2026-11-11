/**
 * Builds a minimal but structurally valid one-page PDF.
 *
 * The backend rejects uploads whose content type or extension is wrong, and
 * then checks the `%PDF-` magic bytes before accepting. A real PDF keeps the
 * async processing path (PDFBox text extraction) realistic instead of forcing
 * the document into FAILED.
 */
export function createPdf(text = 'VerityAI end-to-end test document'): Buffer {
  const safeText = text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const stream = `BT /F1 18 Tf 72 720 Td (${safeText}) Tj ET`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    `<< /Length ${Buffer.byteLength(stream, 'latin1')} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];

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

/** A payload that looks like a PDF to the browser but fails the server gate. */
export function createFakePdf(): Buffer {
  return Buffer.from('This is not a PDF document.\n', 'utf8');
}
