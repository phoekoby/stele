package dev.stele.extractors

// Generic programming/framework tokens that are noise for a product ontology.
// Real concept separation is Phase 3; this is just a cheap signal filter.
internal val STOPWORDS = setOf(
    "type", "types", "string", "str", "name", "value", "val", "data", "id", "db", "key", "node", "path",
    "src", "dist", "out", "build", "version", "script", "scripts", "dependency", "dependencies", "option",
    "options", "config", "index", "util", "utils", "const", "let", "var", "return", "import", "export", "from",
    "function", "fn", "func", "method", "class", "interface", "enum", "void", "int", "bool", "boolean", "number",
    "num", "float", "double", "object", "obj", "array", "arr", "true", "false", "null", "undefined", "none",
    "this", "self", "new", "get", "set", "args", "arg", "param", "params", "result", "res", "req", "error", "err",
    "exception", "item", "items", "list", "map", "default", "module", "modules", "require", "async", "await",
    "public", "private", "protected", "static", "final", "override", "length", "size", "count", "ref", "title",
    "kind", "layer", "source", "field", "row", "col", "tmp", "temp", "test", "tests", "spec", "print", "log",
    "println", "todo", "main", "init", "run",
)
