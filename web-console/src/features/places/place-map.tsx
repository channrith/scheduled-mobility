"use client";

import "maplibre-gl/dist/maplibre-gl.css";
import * as maplibregl from "maplibre-gl";
import { useEffect, useRef } from "react";

// The ESM build loads its worker from a file next to itself, which the bundler does not emit; reference
// the worker so it is emitted as an asset, and point MapLibre at it.
maplibregl.setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).href);

/** OpenStreetMap raster tiles; the attribution is required by the OSM tile usage policy. */
const STYLE: maplibregl.StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">OpenStreetMap</a> contributors',
    },
  },
  layers: [{ id: "osm", type: "raster", source: "osm" }],
};

/** Cambodia, with some margin; the backend rejects pins outside it. */
const BOUNDS: maplibregl.LngLatBoundsLike = [[101.5, 9.5], [108.5, 15.0]];

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
    const m = new maplibregl.Map({
      container: container.current,
      style: STYLE,
      center: [longitude, latitude],
      zoom: 16,
      maxBounds: BOUNDS,
      attributionControl: { compact: true },
    });
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
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
