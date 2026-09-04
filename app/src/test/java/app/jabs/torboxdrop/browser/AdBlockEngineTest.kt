package app.jabs.torboxdrop.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdBlockEngineTest {
    @Test
    fun rulesMatchExactHostsAndSubdomainsAtLabelBoundaries() {
        val rules = setOf("doubleclick.net", "google-analytics.com")

        assertThat(AdBlockEngine.hostMatchesAnyRule("doubleclick.net", rules)).isTrue()
        assertThat(AdBlockEngine.hostMatchesAnyRule("stats.doubleclick.net", rules)).isTrue()
        assertThat(AdBlockEngine.hostMatchesAnyRule("www.google-analytics.com", rules)).isTrue()
        assertThat(AdBlockEngine.hostMatchesAnyRule("notdoubleclick.net", rules)).isFalse()
        assertThat(AdBlockEngine.hostMatchesAnyRule("doubleclick.net.example.org", rules)).isFalse()
    }

    @Test
    fun commonHostFileAndFilterRuleFormsAreParsedSafely() {
        assertThat(AdBlockEngine.parseRule("0.0.0.0 ads.example.com")).isEqualTo("ads.example.com")
        assertThat(AdBlockEngine.parseRule("||tracker.example^ # comment")).isEqualTo("tracker.example")
        assertThat(AdBlockEngine.parseRule("! filter-list comment")).isNull()
        assertThat(AdBlockEngine.parseRule("bad_host.example")).isNull()
    }

    @Test
    fun hostsAreCanonicalizedWithoutSubstringGuessing() {
        assertThat(AdBlockEngine.canonicalHost("STATS.Example.COM.")).isEqualTo("stats.example.com")
        assertThat(AdBlockEngine.canonicalHost("-invalid.example")).isNull()
        assertThat(AdBlockEngine.hostMatchesAnyRule("ordinary-adjective.example", setOf("ad"))).isFalse()
    }
}
