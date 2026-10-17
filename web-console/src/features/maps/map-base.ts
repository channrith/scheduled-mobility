"use client";

import "maplibre-gl/dist/maplibre-gl.css";
import * as maplibregl from "maplibre-gl";

// The ESM build loads its worker from a file next to itself, which the bundler does not emit; reference
// the worker so it is emitted as an asset, and point MapLibre at it.
maplibregl.setWorkerUrl(new URL("maplibre-gl/dist/maplibre-gl-worker.mjs", import.meta.url).href);

export { maplibregl };

/** OpenStreetMap raster tiles; the attribution is required by the OSM tile usage policy. */
export const OSM_STYLE: maplibregl.StyleSpecification = {
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

/** Cambodia, with some margin; the backend rejects positions outside it. */
export const CAMBODIA_BOUNDS: maplibregl.LngLatBoundsLike = [[101.5, 9.5], [108.5, 15.0]];

/** Central Phnom Penh (Independence Monument). */
export const PHNOM_PENH = { latitude: 11.5564, longitude: 104.9282 };

export function newMap(container: HTMLElement, center: { latitude: number; longitude: number }, zoom: number) {
  const map = new maplibregl.Map({
    container,
    style: OSM_STYLE,
    center: [center.longitude, center.latitude],
    zoom,
    maxBounds: CAMBODIA_BOUNDS,
    attributionControl: { compact: true },
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  return map;
}
