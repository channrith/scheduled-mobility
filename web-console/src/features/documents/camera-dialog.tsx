"use client";

import { Camera, RotateCcw } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** Laptop/desktop webcam capture. Phones and tablets use the native camera via <input capture>. */
export function CameraDialog({
  open,
  onOpenChange,
  title,
  onCapture,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  onCapture: (file: File) => void;
}) {
  const t = useTranslations("document");
  const video = useRef<HTMLVideoElement>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [shot, setShot] = useState<Blob | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!open) return;
    let active: MediaStream | null = null;
    navigator.mediaDevices
      ?.getUserMedia({ video: { facingMode: "environment", width: { ideal: 1920 }, height: { ideal: 1080 } } })
      .then((s) => {
        active = s;
        setStream(s);
        if (video.current) video.current.srcObject = s;
      })
      .catch(() => setError(true));
    return () => {
      active?.getTracks().forEach((track) => track.stop());
      setStream(null);
      setShot(null);
      setError(false);
    };
  }, [open]);

  const capture = () => {
    const v = video.current;
    if (!v) return;
    const canvas = document.createElement("canvas");
    canvas.width = v.videoWidth;
    canvas.height = v.videoHeight;
    canvas.getContext("2d")!.drawImage(v, 0, 0);
    canvas.toBlob((blob) => blob && setShot(blob), "image/jpeg", 0.9);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {error || !navigator.mediaDevices ? (
          <p className="text-sm text-muted-foreground">{t("cameraUnavailable")}</p>
        ) : (
          <div className="overflow-hidden rounded-lg bg-black">
            {shot ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={URL.createObjectURL(shot)} alt="" className="max-h-[60vh] w-full object-contain" />
            ) : (
              <video ref={video} autoPlay playsInline muted className="max-h-[60vh] w-full object-contain" />
            )}
          </div>
        )}
        <DialogFooter>
          {shot ? (
            <>
              <Button variant="outline" onClick={() => setShot(null)}>
                <RotateCcw /> {t("retake")}
              </Button>
              <Button
                onClick={() => {
                  onCapture(new File([shot], `capture-${Date.now()}.jpg`, { type: "image/jpeg" }));
                  onOpenChange(false);
                }}
              >
                {t("upload")}
              </Button>
            </>
          ) : (
            <Button onClick={capture} disabled={!stream}>
              <Camera /> {t("capture")}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
