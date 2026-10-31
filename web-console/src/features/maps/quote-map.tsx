"use client";

import { useEffect, useRef } from "react";
import type { LatLng, ZoneView } from "@/lib/api/types";
import { maplibregl, newMap, PHNOM_PENH } from "./map-base";
import { ZONE_COLORS } from "./zone-map";

type Props = {
  zones: ZoneView[];
  pickup: LatLng | null;
  dropoff: LatLng | null;
  /** Which end a map click sets; null = clicks do nothing. */
  picking: "pickup" | "dropoff" | null;
  onPick: (end: "pickup" | "dropoff", position: LatLng) => void;
};

/** Zones for context, the two ends as markers (green pickup, red drop-off). Client only. */
export default function QuoteMap({ zones, pickup, dropoff, picking, onPick }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const markers = useRef<{ pickup?: maplibregl.Marker; dropoff?: maplibregl.Marker }>({});
  const loaded = useRef(false);
  const latest = useRef({ zones, picking, onPick });

  useEffect(() => {
    latest.current = { zones, picking, onPick };
  });

  const showZones = () => {
    const m = map.current;
    if (!m || !loaded.current) return;
    (m.getSource("zones") as maplibregl.GeoJSONSource | undefined)?.setData({
      type: "FeatureCollection",
      features: latest.current.zones
        .filter((z) => z.active && z.polygon.length >= 3)
        .map((z) => {
          const ring = z.polygon.map((p) => [p.longitude, p.latitude]);
          return {
            type: "Feature" as const,
            properties: { type: z.type },
            geometry: { type: "Polygon" as const, coordinates: [[...ring, ring[0]]] },
          };
        }),
    });
  };

  useEffect(() => {
    if (!container.current) return;
    const m = newMap(container.current, PHNOM_PENH, 11);
    map.current = m;
    m.on("load", () => {
      m.addSource("zones", { type: "geojson", data: { type: "FeatureCollection", features: [] } });
      const color: maplibregl.ExpressionSpecification = [
        "match", ["get", "type"], "CITY", ZONE_COLORS.CITY, "AIRPORT", ZONE_COLORS.AIRPORT, ZONE_COLORS.RESTRICTED,
      ];
      m.addLayer({ id: "zones-fill", type: "fill", source: "zones", paint: { "fill-color": color, "fill-opacity": 0.1 } });
      m.addLayer({ id: "zones-line", type: "line", source: "zones", paint: { "line-color": color, "line-width": 1.5 } });
      loaded.current = true;
      showZones();
    });
    m.on("click", (e) => {
      const end = latest.current.picking;
      if (end) latest.current.onPick(end, { latitude: e.lngLat.lat, longitude: e.lngLat.lng });
    });
    return () => {
      loaded.current = false;
      m.remove();
      map.current = null;
      markers.current = {};
    };
    // Created once; later prop changes are applied by the effects below.
     
  }, []);

  useEffect(() => {
    showZones();
  });

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    m.getCanvas().style.cursor = picking ? "crosshair" : "";
  }, [picking]);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    for (const [end, position, color] of [["pickup", pickup, "#16a34a"], ["dropoff", dropoff, "#dc2626"]] as const) {
      const existing = markers.current[end];
      if (!position) {
        existing?.remove();
        delete markers.current[end];
      } else if (existing) {
        existing.setLngLat([position.longitude, position.latitude]);
      } else {
        markers.current[end] = new maplibregl.Marker({ color }).setLngLat([position.longitude, position.latitude]).addTo(m);
      }
    }
    if (pickup && dropoff) {
      m.fitBounds(
        [[Math.min(pickup.longitude, dropoff.longitude), Math.min(pickup.latitude, dropoff.latitude)],
          [Math.max(pickup.longitude, dropoff.longitude), Math.max(pickup.latitude, dropoff.latitude)]],
        { padding: 80, maxZoom: 15, duration: 400 },
      );
    } else if (pickup || dropoff) {
      const p = (pickup ?? dropoff)!;
      m.easeTo({ center: [p.longitude, p.latitude] });
    }
  }, [pickup, dropoff]);

  return <div ref={container} className="h-full min-h-96 w-full overflow-hidden rounded-lg border" />;
}
