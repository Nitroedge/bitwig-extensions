#!/usr/bin/env node

/**
 * Generates an OpenAPI 3.1 spec from claude-tools.json.
 *
 * Each Claude tool (e.g. "transport_play") maps to a POST path
 * (e.g. "/transport/play") wrapping the tool's input_schema
 * inside a JSON-RPC 2.0 request envelope.
 *
 * Usage: node scripts/generate-openapi.mjs
 * Output: docs/openapi.json
 */

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..");

const tools = JSON.parse(readFileSync(join(root, "tools/claude-tools.json"), "utf-8"));

// Derive tag (domain) from tool name: "transport_play" → "transport"
function getTag(name) {
  const prefix = name.split("_")[0];
  const tagMap = {
    session: "Session",
    api: "Session",
    transport: "Transport",
    track: "Track",
    trackBank: "Track Bank",
    master: "Master",
    cursor: "Cursor",
    clip: "Clip",
    note: "Note",
    noteInput: "Note Input",
    scene: "Scene",
    sceneBank: "Scene Bank",
    device: "Device",
    masterDevice: "Master Device",
    browser: "Browser",
    arranger: "Arranger",
    arrangement: "Arranger",
    detailEditor: "Detail Editor",
    mixer: "Mixer",
    project: "Project",
    groove: "Groove",
    send: "Send",
    arpeggiator: "Arpeggiator",
    noteLatch: "Note Latch",
    cueMarker: "Cue Marker",
    cueMarkerBank: "Cue Marker Bank",
    macro: "Macro",
    state: "State",
    action: "Action",
    app: "Application",
  };
  // Handle multi-word prefixes like "masterDevice_selectNext"
  for (const key of Object.keys(tagMap).sort((a, b) => b.length - a.length)) {
    if (name.startsWith(key + "_")) return tagMap[key];
  }
  return tagMap[prefix] || prefix;
}

// Convert tool name to RPC method path: "transport_play" → "/transport/play"
function toPath(name) {
  // Handle multi-segment domains: masterDevice_selectNext → /masterDevice/selectNext
  // Find the longest matching prefix
  const domains = [
    "session", "api", "transport", "track", "trackBank", "master",
    "cursor", "clip", "note", "noteInput", "scene", "sceneBank",
    "device", "masterDevice", "browser", "arranger", "arrangement",
    "detailEditor", "mixer", "project", "groove", "send",
    "arpeggiator", "noteLatch", "cueMarker", "cueMarkerBank",
    "macro", "state", "action", "app",
  ].sort((a, b) => b.length - a.length);

  for (const domain of domains) {
    if (name.startsWith(domain + "_")) {
      const method = name.slice(domain.length + 1);
      // Convert remaining underscores to camelCase continuation (they're already camelCase)
      return `/${domain}/${method}`;
    }
  }
  // Fallback: first underscore splits domain/method
  const idx = name.indexOf("_");
  if (idx === -1) return `/${name}`;
  return `/${name.slice(0, idx)}/${name.slice(idx + 1)}`;
}

// Convert tool name to RPC method name: "transport_play" → "transport/play"
function toRpcMethod(name) {
  return toPath(name).slice(1); // strip leading /
}

// Context-aware example values keyed by parameter name
const examplesByName = {
  // Indices and positions
  index: 0, trackIndex: 0, slotIndex: 0, slot: 0, sceneIndex: 0,
  position: 0, insertPosition: "end", page: 0,
  // Values
  value: 0.8, volume: 0.8, level: 0.8, pan: 0.5, amount: 0.5,
  mix: 0.5, gain: 0.5, threshold: 64,
  // Transport
  bpm: 120.0, tempo: 120.0, beats: 4.0,
  start: 0.0, length: 4.0, duration: 1.0,
  // Notes
  x: 0, y: 60, velocity: 100, channel: 0,
  key: 60, octaves: 2, steps: 4,
  stepSize: 0.25, chance: 1.0,
  // Text
  name: "My Track", color: "BLUE", id: "select_all",
  notification: "Hello from Secondo",
  category: "Transport",
  // Devices
  deviceName: "Polymer", pluginId: "com.example.synth",
  pluginType: "vst3",
  // Modes and enums
  mode: "latch", rate: "1/16", layout: "ARRANGE",
  section: "sends", type: "instrument",
  action: "forward",
  // Booleans
  enabled: true, mute: false, solo: false, arm: false,
  loop: true, expanded: true, pinned: false,
  audition: true, mono: false,
  // Complex
  operations: [{ method: "transport/play", params: {} }],
  notes: [{ x: 0, y: 60, velocity: 100, duration: 1.0 }],
  parameters: [{ page: 0, index: 0, value: 0.5 }],
  // Clip expressions
  timbre: 0.5, pressure: 0.5, transpose: 0, releaseVelocity: 64,
  velocitySpread: 0.0,
  // Automation
  points: [{ position: 0.0, value: 0.5 }, { position: 4.0, value: 1.0 }],
  // Subscriptions
  topics: ["transport", "tracks"],
  // Misc
  offset: 0, count: 8, semitones: 0, gridSize: 0.25,
  crossfadeMode: "AB", monitorMode: "AUTO",
  preSnapshot: false, postSnapshot: false,
  rollback: "undoAll",
};

// Build example params from schema with context-aware values
function buildExample(schema) {
  const example = {};
  if (!schema.properties) return example;
  for (const [key, prop] of Object.entries(schema.properties)) {
    // Infer from description hints before falling back to name-based defaults
    const desc = (prop.description || "").toLowerCase();
    if (key === "name" && (desc.includes("device") || desc.includes("polymer"))) {
      example[key] = "Polymer";
      continue;
    }
    if (key === "name" && (desc.includes("scene") || desc.includes("clip"))) {
      example[key] = "Verse A";
      continue;
    }
    // Use known example if available
    if (key in examplesByName) {
      example[key] = examplesByName[key];
      continue;
    }
    // Use enum first value
    if (prop.enum) {
      example[key] = prop.enum[0];
      continue;
    }
    // Infer from name patterns
    if (prop.type === "string") {
      if (key.toLowerCase().includes("name")) example[key] = "My Item";
      else if (key.toLowerCase().includes("color")) example[key] = "RED";
      else if (key.toLowerCase().includes("id")) example[key] = "example_id";
      else if (key.toLowerCase().includes("method")) example[key] = "transport/play";
      else example[key] = "value";
    } else if (prop.type === "number") {
      if (key.toLowerCase().includes("volume") || key.toLowerCase().includes("pan")
          || key.toLowerCase().includes("value") || key.toLowerCase().includes("level"))
        example[key] = 0.8;
      else if (key.toLowerCase().includes("tempo") || key.toLowerCase().includes("bpm"))
        example[key] = 120.0;
      else example[key] = 1.0;
    } else if (prop.type === "integer") {
      if (key.toLowerCase().includes("index")) example[key] = 0;
      else if (key.toLowerCase().includes("velocity")) example[key] = 100;
      else if (key.toLowerCase().includes("key") || key.toLowerCase().includes("note"))
        example[key] = 60;
      else example[key] = 1;
    } else if (prop.type === "boolean") {
      example[key] = true;
    } else if (prop.type === "array") {
      example[key] = [];
    } else if (prop.type === "object") {
      example[key] = {};
    }
  }
  return example;
}

// Add example values to individual properties in the schema (for inline display)
function enrichSchemaWithExamples(schema) {
  if (!schema.properties) return schema;
  const enriched = { ...schema, properties: { ...schema.properties } };
  for (const [key, prop] of Object.entries(enriched.properties)) {
    if (prop.example !== undefined) continue; // already has one
    const val = key in examplesByName ? examplesByName[key] : undefined;
    if (val !== undefined && typeof val !== "object") {
      enriched.properties[key] = { ...prop, example: val };
    } else if (prop.enum) {
      enriched.properties[key] = { ...prop, example: prop.enum[0] };
    }
  }
  return enriched;
}

// Build paths
const paths = {};
const tagSet = new Set();

for (const tool of tools) {
  const path = toPath(tool.name);
  const rpcMethod = toRpcMethod(tool.name);
  const tag = getTag(tool.name);
  tagSet.add(tag);

  const hasParams =
    tool.input_schema.properties &&
    Object.keys(tool.input_schema.properties).length > 0;

  const requestBody = {
    required: true,
    content: {
      "application/json": {
        schema: {
          type: "object",
          required: ["jsonrpc", "method", "id"],
          properties: {
            jsonrpc: { type: "string", enum: ["2.0"], description: "JSON-RPC version" },
            method: { type: "string", enum: [rpcMethod], description: "RPC method name" },
            id: { type: "integer", description: "Request identifier", example: 1 },
            params: hasParams
              ? { ...enrichSchemaWithExamples(tool.input_schema), description: "Method parameters" }
              : { type: "object", properties: {}, description: "No parameters required" },
          },
        },
        example: {
          jsonrpc: "2.0",
          method: rpcMethod,
          params: hasParams ? buildExample(tool.input_schema) : {},
          id: 1,
        },
      },
    },
  };

  paths[path] = {
    post: {
      tags: [tag],
      summary: firstSentence(tool.description),
      description: tool.description,
      operationId: tool.name,
      requestBody,
      responses: {
        200: {
          description: "JSON-RPC 2.0 response",
          content: {
            "application/json": {
              schema: { $ref: "#/components/schemas/JsonRpcResponse" },
            },
          },
        },
      },
    },
  };
}

function firstSentence(text) {
  const match = text.match(/^(.+?\.)\s/);
  return match ? match[1] : text.slice(0, 120);
}

// Sort tags in logical order
const tagOrder = [
  "Session", "Transport", "Track", "Track Bank", "Master", "Cursor",
  "Clip", "Note", "Note Input", "Scene", "Scene Bank",
  "Device", "Master Device", "Browser",
  "Arranger", "Detail Editor", "Mixer", "Project", "Groove", "Send",
  "Arpeggiator", "Note Latch", "Cue Marker", "Cue Marker Bank",
  "Macro", "State", "Action", "Application",
];

const tags = tagOrder
  .filter((t) => tagSet.has(t))
  .map((name) => ({ name }));

const spec = {
  openapi: "3.1.0",
  info: {
    title: "Secondo — Bitwig Studio RPC API",
    version: "0.42.0",
    description:
      "Control Bitwig Studio programmatically via JSON-RPC 2.0. " +
      "All methods are invoked as POST requests to a single endpoint (`/rpc`). " +
      "This spec models each RPC method as its own path for interactive documentation. " +
      "In practice, all requests go to `POST /rpc` with the method name in the JSON body.\n\n" +
      "**HTTP Endpoint:** `POST http://localhost:8787/rpc`\n\n" +
      "**WebSocket Endpoint:** `ws://localhost:8788/` (real-time state streaming)\n\n" +
      "**Protocol:** JSON-RPC 2.0",
  },
  servers: [
    {
      url: "http://localhost:8787",
      description: "Local Bitwig Studio (Secondo extension)",
    },
  ],
  tags,
  paths,
  components: {
    schemas: {
      JsonRpcResponse: {
        type: "object",
        properties: {
          jsonrpc: { type: "string", enum: ["2.0"] },
          result: { type: "object", description: "Method-specific result data" },
          error: {
            type: "object",
            properties: {
              code: { type: "integer", description: "Error code" },
              message: { type: "string", description: "Error message" },
            },
          },
          id: { type: "integer", description: "Echoed request identifier" },
        },
      },
    },
  },
};

const output = join(root, "docs/openapi.json");
writeFileSync(output, JSON.stringify(spec, null, 2) + "\n");
console.log(`Generated ${output} — ${tools.length} methods, ${tags.length} tags`);
