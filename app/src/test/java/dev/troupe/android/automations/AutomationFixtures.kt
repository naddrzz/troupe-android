package dev.troupe.android.automations

/** The Automations contract fixtures under `app/src/test/resources/fixtures/automations/`. */
object AutomationFixtures {
    fun load(name: String): String =
        checkNotNull(AutomationFixtures::class.java.getResource("/fixtures/automations/$name")) { "missing fixture $name" }.readText()
}
