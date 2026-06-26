export type Layer = "code" | "design" | "product" | "evidence" | "config";

export type ArtifactKind =
  | "code_symbol"
  | "file"
  | "frame"
  | "component"
  | "issue"
  | "pr"
  | "commit"
  | "doc"
  | "config";

export type EdgeType =
  | "implements"
  | "describes"
  | "depicts"
  | "constrains"
  | "changed"
  | "references"
  | "belongs_to"
  | "relates";

export type EdgeSource = "deterministic" | "inferred" | "human";
export type EdgeStatus = "proposed" | "confirmed" | "rejected";
export type ConceptStatus = "candidate" | "confirmed";

export interface Concept {
  id: string;
  name: string;
  definition?: string | null;
  bounded_context?: string | null;
  aliases: string[];
  status: ConceptStatus;
}

export interface Artifact {
  id: string;
  kind: ArtifactKind;
  layer: Layer;
  source: string;
  ref: string;
  title?: string | null;
  body?: string | null;
  attrs?: Record<string, unknown>;
}

export interface Mention {
  id: string;
  artifact_id: string;
  term: string;
  normalized: string;
  span?: string | null;
}

export interface Edge {
  id: string;
  src_id: string;
  dst_id: string;
  type: EdgeType;
  source: EdgeSource;
  confidence: number;
  evidence: string[];
  status: EdgeStatus;
}

export interface GraphCounts {
  concepts: number;
  artifacts: number;
  mentions: number;
  edges: number;
}
