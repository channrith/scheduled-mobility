"use client";

import { Camera, FileText, FolderOpen, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { CameraDialog } from "./camera-dialog";
import { MAX_BYTES, isCoarsePointer, prepareFile } from "./image-utils";

/** One side of a document: take a photo (camera) or choose a file, with a thumbnail. */
export function FilePicker({
  label,
  value,
  onChange,
  allowPdf = true,
}: {
  label: string;
  value: File | null;
  onChange: (file: File | null) => void;
  allowPdf?: boolean;
}) {
  const t = useTranslations("document");
  const cameraInput = useRef<HTMLInputElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const thumb = useMemo(() => (value && value.type.startsWith("image/") ? URL.createObjectURL(value) : null), [value]);
  useEffect(() => () => {
    if (thumb) URL.revokeObjectURL(thumb);
  }, [thumb]);

  const accept = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    setBusy(true);
    try {
      const prepared = await prepareFile(file);
      if (prepared.size > MAX_BYTES) {
        setError(t("tooLarge"));
        return;
      }
      onChange(prepared);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{label}</span>
        {value && (
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t("replace")} onClick={() => onChange(null)}>
            <X />
          </Button>
        )}
      </div>
      <div className="flex aspect-[3/2] items-center justify-center overflow-hidden rounded-md bg-muted">
        {thumb ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={thumb} alt={label} className="h-full w-full object-contain" />
        ) : value ? (
          <div className="flex flex-col items-center gap-1 text-sm text-muted-foreground">
            <FileText className="size-8" />
            <span className="max-w-48 truncate">{value.name}</span>
          </div>
        ) : (
          <span className="text-sm text-muted-foreground">{busy ? t("compressing") : t("notUploaded")}</span>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <Button
          type="button"
          variant="outline"
          disabled={busy}
          onClick={() => (isCoarsePointer() ? cameraInput.current?.click() : setCameraOpen(true))}
        >
          <Camera /> {t("takePhoto")}
        </Button>
        <Button type="button" variant="outline" disabled={busy} onClick={() => fileInput.current?.click()}>
          <FolderOpen /> {t("chooseFile")}
        </Button>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <input
        ref={cameraInput}
        type="file"
        accept="image/*"
        capture="environment"
        hidden
        onChange={(e) => {
          void accept(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      <input
        ref={fileInput}
        type="file"
        accept={allowPdf ? "image/jpeg,image/png,application/pdf" : "image/jpeg,image/png"}
        hidden
        onChange={(e) => {
          void accept(e.target.files?.[0]);
          e.target.value = "";
        }}
      />
      <CameraDialog open={cameraOpen} onOpenChange={setCameraOpen} title={label} onCapture={(f) => void accept(f)} />
    </div>
  );
}
