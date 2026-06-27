package dev.stele.connectors.docs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleExtractionTest {

    @Test
    fun `keeps genuine product-constraint sentences`() {
        val rules = listOf(
            "It must never be forwarded to API clients.",
            "You cannot create system tags yourself.",
            "RBAC is deny-by-default; a new action requires a matrix entry.",
            "The access token contains only identity claims.",
            "Операции только на чтение требуют лишь валидного токена.",
        )
        for (r in rules) assertTrue(isProseRule(r), "should keep: $r")
    }

    @Test
    fun `drops markdown tables, urls and schema fragments`() {
        val noise = listOf(
            "| `POST` | `/auth/signup` | `username`, `email`, `password` required | 200 |",
            "postgresql://<username>:<password>@<host>:5432/<db>?sslmode=require",
            "uuid plan_id FK \"только kind=project\"",
            "| `apps/proxy-ql-executor` | Serverless read-only SQL | only |",
            "`a` `b` `c` `d` `e` must hold",
        )
        for (n in noise) assertFalse(isProseRule(n), "should drop: $n")
    }

    @Test
    fun `drops plain prose with no constraint keyword`() {
        assertFalse(isProseRule("This document maps the api surface for developers."))
    }
}
