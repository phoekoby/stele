package dev.stele.core.model

/** The string-literal unions from the data model, as enums carrying their DB value. */

enum class Layer(val value: String) {
    CODE("code"),
    DESIGN("design"),
    PRODUCT("product"),
    EVIDENCE("evidence"),
    CONFIG("config"),
}

enum class ArtifactKind(val value: String) {
    CODE_SYMBOL("code_symbol"),
    FILE("file"),
    FRAME("frame"),
    COMPONENT("component"),
    ISSUE("issue"),
    PR("pr"),
    COMMIT("commit"),
    FEATURE("feature"),
    DOC("doc"),
    RULE("rule"),
    CONFIG("config"),
}

enum class EdgeType(val value: String) {
    IMPLEMENTS("implements"),
    DESCRIBES("describes"),
    DEPICTS("depicts"),
    CONSTRAINS("constrains"),
    CHANGED("changed"),
    REFERENCES("references"),
    BELONGS_TO("belongs_to"),
    RELATES("relates"),
    CALLS("calls"),
    IMPORTS("imports"),
}

enum class EdgeSource(val value: String) {
    DETERMINISTIC("deterministic"),
    INFERRED("inferred"),
    HUMAN("human"),
}

enum class EdgeStatus(val value: String) {
    PROPOSED("proposed"),
    CONFIRMED("confirmed"),
    REJECTED("rejected"),
}

enum class ConceptStatus(val value: String) {
    CANDIDATE("candidate"),
    CONFIRMED("confirmed"),
}
