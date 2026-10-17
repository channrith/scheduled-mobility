"use client";

import { useEffect, useRef } from "react";
import { maplibregl, newMap } from "./map-base";

/**
 * Map with one draggable pin. Dragging the pin or clicking the map moves it; changes made outside (typed
 * coordinates) move the pin and recentre the map. Client only: loaded with next/dynamic, ssr false.
 */
export default function PlaceMap({
  latitude,
  longitude,
  draggable,
  onMove,
}: {
  latitude: number;
  longitude: number;
  draggable: boolean;
  onMove: (latitude: number, longitude: number) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const marker = useRef<maplibregl.Marker | null>(null);
  const onMoveRef = useRef(onMove);
  const draggableRef = useRef(draggable);

  useEffect(() => {
    onMoveRef.current = onMove;
    draggableRef.current = draggable;
  });

  useEffect(() => {
    if (!container.current) return;
    const m = newMap(container.current, { latitude, longitude }, 16);
    const pin = new maplibregl.Marker({ draggable, color: "#dc2626" }).setLngLat([longitude, latitude]).addTo(m);
    pin.on("dragend", () => {
      const p = pin.getLngLat();
      onMoveRef.current(p.lat, p.lng);
    });
    m.on("click", (e) => {
      if (!draggableRef.current) return;
      pin.setLngLat(e.lngLat);
      onMoveRef.current(e.lngLat.lat, e.lngLat.lng);
    });
    map.current = m;
    marker.current = pin;
    return () => {
      m.remove();
      map.current = null;
      marker.current = null;
    };
    // The map is created once; later prop changes are applied by the effects below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    marker.current?.setDraggable(draggable);
  }, [draggable]);

  useEffect(() => {
    const pin = marker.current;
    if (!pin || !Number.isFinite(latitude) || !Number.isFinite(longitude)) return;
    const current = pin.getLngLat();
    if (Math.abs(current.lat - latitude) < 1e-7 && Math.abs(current.lng - longitude) < 1e-7) return;
    pin.setLngLat([longitude, latitude]);
    map.current?.easeTo({ center: [longitude, latitude] });
  }, [latitude, longitude]);

  return <div ref={container} className="h-full min-h-80 w-full overflow-hidden rounded-lg border" />;
}
