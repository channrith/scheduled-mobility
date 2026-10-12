export const MAX_BYTES = 10 * 1024 * 1024;
const MAX_EDGE = 2400;

/**
 * Phone photos are often 5–12 MB. Downscale to at most 2400 px and re-encode as JPEG, which keeps ID cards
 * readable and well under the 10 MB limit. PDFs and small images are left untouched.
 */
export async function prepareFile(file: File): Promise<File> {
  if (!file.type.startsWith("image/") || (file.size < 2_500_000 && file.type !== "image/heic")) {
    return file;
  }
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("encode failed"))), "image/jpeg", 0.85),
  );
  return new File([blob], file.name.replace(/\.\w+$/, "") + ".jpg", { type: "image/jpeg" });
}

export function isCoarsePointer() {
  return typeof window !== "undefined" && window.matchMedia("(pointer: coarse)").matches;
}
