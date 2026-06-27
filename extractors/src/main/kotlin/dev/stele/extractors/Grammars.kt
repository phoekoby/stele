package dev.stele.extractors

import org.treesitter.TSLanguage
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterCSharp
import org.treesitter.TreeSitterCpp
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterLua
import org.treesitter.TreeSitterPhp
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterRuby
import org.treesitter.TreeSitterRust
import org.treesitter.TreeSitterScala
import org.treesitter.TreeSitterSwift
import org.treesitter.TreeSitterTypescript

/** File extension -> grammar key (code only; config/markup excluded as noise). */
val EXT_TO_LANG: Map<String, String> = mapOf(
    ".ts" to "typescript",
    ".tsx" to "tsx",
    ".js" to "javascript",
    ".jsx" to "javascript",
    ".mjs" to "javascript",
    ".cjs" to "javascript",
    ".py" to "python",
    ".go" to "go",
    ".rs" to "rust",
    ".java" to "java",
    ".kt" to "kotlin",
    ".kts" to "kotlin",
    ".rb" to "ruby",
    ".php" to "php",
    ".swift" to "swift",
    ".scala" to "scala",
    ".cs" to "c_sharp",
    ".c" to "c",
    ".h" to "c",
    ".cpp" to "cpp",
    ".cc" to "cpp",
    ".hpp" to "cpp",
    ".lua" to "lua",
)

/**
 * Grammar key -> language factory. Only keys present here are parsed; an
 * extension whose grammar isn't registered (e.g. `tsx` — bonede ships no TSX
 * binding) is gracefully skipped, recorded in [IngestResult.skippedLangs].
 */
val GRAMMARS: Map<String, () -> TSLanguage> = mapOf(
    "typescript" to { TreeSitterTypescript() },
    "javascript" to { TreeSitterJavascript() },
    "python" to { TreeSitterPython() },
    "go" to { TreeSitterGo() },
    "rust" to { TreeSitterRust() },
    "java" to { TreeSitterJava() },
    "kotlin" to { TreeSitterKotlin() },
    "ruby" to { TreeSitterRuby() },
    "php" to { TreeSitterPhp() },
    "swift" to { TreeSitterSwift() },
    "scala" to { TreeSitterScala() },
    "c_sharp" to { TreeSitterCSharp() },
    "c" to { TreeSitterC() },
    "cpp" to { TreeSitterCpp() },
    "lua" to { TreeSitterLua() },
)
