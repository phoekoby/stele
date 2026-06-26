/**
 * Split an identifier into normalized lowercase tokens — the raw material
 * for the ubiquitous-language concept layer.
 *   validateRefund   -> ["validate", "refund"]
 *   REFUND_WINDOW     -> ["refund", "window"]
 *   user/refund.flow  -> ["user", "refund", "flow"]
 */
export function splitIdentifier(id: string): string[] {
  return id
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2") // camelCase boundary
    .replace(/[_\-./]+/g, " ") // snake / kebab / path separators
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 1 && !/^\d+$/.test(t));
}

export const normalize = (term: string): string =>
  splitIdentifier(term).join(" ");
