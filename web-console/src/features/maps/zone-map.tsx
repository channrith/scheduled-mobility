"use client";

import { useEffect, useRef } from "react";
import { TerraDraw, TerraDrawPolygonMode, TerraDrawSelectMode } from "terra-draw";
import { TerraDrawMapLibreGLAdapter } from "terra-draw-maplibre-gl-adapter";
import type { LatLng, ZoneType, ZoneView } from "@/lib/api/types";
import { maplibregl, newMap, PHNOM_PENH } from "./map-base";

export const ZONE_COLORS: Record<ZoneType, string> = { CITY: "#2563eb", AIRPORT: "#d97706", RESTRICTED: "#dc2626" };

/** null: not editing. polygon null: drawing a new shape. */
export type ZoneEditing = { key: string; polygon: LatLng[] | null } | null;

type Props = {
  zones: ZoneView[];
  selectedId?: string;
  editing: ZoneEditing;
  onSelect: (id: string) => void;
  onPolygonChange: (polygon: LatLng[]) => void;
};

function ring(points: LatLng[]) {
  const coords = points.map((p) => [p.longitude, p.latitude]);
  return [...coords, coords[0]];
}

function toFeatureCollection(zones: ZoneView[], hiddenId?: string): GeoJSON.FeatureCollection {
  return {
    type: "FeatureCollection",
    features: zones
      .filter((z) => z.id !== hiddenId && z.polygon.length >= 3)
      .map((z) => ({
        type: "Feature",
        properties: { id: z.id, type: z.type, active: z.active },
        geometry: { type: "Polygon", coordinates: [ring(z.polygon)] },
      })),
  };
}

function bounds(points: LatLng[]): maplibregl.LngLatBoundsLike {
  const lngs = points.map((p) => p.longitude);
  const lats = points.map((p) => p.latitude);
  return [[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]];
}

/**
 * All zones coloured by type; click one to select it. While editing, one polygon is drawn (click points,
 * click the first point to close) or edited (drag corners, drag a midpoint to add a corner) with terra-draw.
 * Client only: loaded with next/dynamic, ssr false.
 */
export default function ZoneMap({ zones, selectedId, editing, onSelect, onPolygonChange }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const draw = useRef<TerraDraw | null>(null);
  const loaded = useRef(false);
  const latest = useRef({ onSelect, onPolygonChange, editing, zones, selectedId });

  useEffect(() => {
    latest.current = { onSelect, onPolygonChange, editing, zones, selectedId };
  });

  /** Zones on the map; the one being edited is shown by terra-draw instead. */
  const showZones = () => {
    const m = map.current;
    if (!m || !loaded.current) return;
    const { editing: current, zones: all, selectedId: selected } = latest.current;
    (m.getSource("zones") as maplibregl.GeoJSONSource | undefined)?.setData(toFeatureCollection(all, current ? selected : undefined));
    m.setPaintProperty("zones-line", "line-width", ["case", ["==", ["get", "id"], selected ?? ""], 4, 2]);
  };

  /** Starts (or stops) drawing/editing for the current {@code editing}. */
  const applyEditing = () => {
    const td = draw.current;
    if (!td || !loaded.current) return;
    td.clear();
    const current = latest.current.editing;
    if (!current) {
      td.setMode("static");
      return;
    }
    if (current.polygon && current.polygon.length >= 3) {
      const [result] = td.addFeatures([
        { type: "Feature", properties: { mode: "polygon" }, geometry: { type: "Polygon", coordinates: [ring(current.polygon)] } },
      ]);
      td.setMode("select");
      if (result?.valid) td.selectFeature(result.id as string);
    } else {
      td.setMode("polygon");
    }
  };

  useEffect(() => {
    if (!container.current) return;
    const m = newMap(container.current, PHNOM_PENH, 10);
    map.current = m;
    m.on("load", () => {
      m.addSource("zones", { type: "geojson", data: toFeatureCollection([]) });
      const color: maplibregl.ExpressionSpecification = [
        "match", ["get", "type"], "CITY", ZONE_COLORS.CITY, "AIRPORT", ZONE_COLORS.AIRPORT, ZONE_COLORS.RESTRICTED,
      ];
      m.addLayer({
        id: "zones-fill",
        type: "fill",
        source: "zones",
        paint: { "fill-color": color, "fill-opacity": ["case", ["get", "active"], 0.18, 0.05] },
      });
      m.addLayer({
        id: "zones-line",
        type: "line",
        source: "zones",
        paint: {
          "line-color": color,
          "line-width": ["case", ["==", ["get", "id"], ""], 4, 2],
          "line-dasharray": ["case", ["get", "active"], ["literal", [1, 0]], ["literal", [2, 2]]],
        },
      });
      m.on("click", "zones-fill", (e) => {
        if (latest.current.editing) return;
        const id = e.features?.[0]?.properties?.id;
        if (typeof id === "string") latest.current.onSelect(id);
      });
      m.on("mouseenter", "zones-fill", () => {
        if (!latest.current.editing) m.getCanvas().style.cursor = "pointer";
      });
      m.on("mouseleave", "zones-fill", () => {
        m.getCanvas().style.cursor = "";
      });

      const td = new TerraDraw({
        adapter: new TerraDrawMapLibreGLAdapter({ map: m }),
        modes: [
          new TerraDrawPolygonMode({ styles: { fillColor: "#7c3aed", outlineColor: "#7c3aed", fillOpacity: 0.25, outlineWidth: 2 } }),
          new TerraDrawSelectMode({
            flags: {
              polygon: {
                feature: { draggable: true, coordinates: { draggable: true, midpoints: true, deletable: true } },
              },
            },
            styles: { selectedPolygonColor: "#7c3aed", selectedPolygonOutlineColor: "#7c3aed", selectedPolygonFillOpacity: 0.25 },
          }),
        ],
      });
      td.start();
      const emit = (id: string | number) => {
        const feature = td.getSnapshotFeature(id);
        if (!feature || feature.geometry.type !== "Polygon") return;
        const outer = feature.geometry.coordinates[0];
        latest.current.onPolygonChange(outer.slice(0, -1).map(([lng, lat]) => ({ latitude: lat, longitude: lng })));
      };
      td.on("finish", (id) => {
        emit(id);
        if (td.getMode() === "polygon") {
          // Drawn: switch to editing the new shape.
          td.setMode("select");
          td.selectFeature(id);
        }
      });
      td.on("change", (ids, type) => {
        if (type === "update" && td.getMode() === "select") ids.forEach(emit);
      });
      draw.current = td;
      loaded.current = true;
      showZones();
      applyEditing();
    });
    return () => {
      draw.current?.stop();
      draw.current = null;
      loaded.current = false;
      m.remove();
      map.current = null;
    };
    // Created once; later prop changes are applied by showZones() and applyEditing().
     
  }, []);

  useEffect(() => {
    showZones();
  });

  // Only when a new edit starts (by key), not on every polygon change.
  const editingKey = editing?.key;
  useEffect(() => {
    applyEditing();
     
  }, [editingKey]);

  // Show the selected zone.
  useEffect(() => {
    const zone = zones.find((z) => z.id === selectedId);
    if (zone && zone.polygon.length >= 3) map.current?.fitBounds(bounds(zone.polygon), { padding: 60, maxZoom: 15, duration: 400 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  return <div ref={container} className="h-full min-h-96 w-full overflow-hidden rounded-lg border" />;
}
