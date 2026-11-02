package io.pickwick.app

import io.pickwick.app.data.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {

    private fun score(query: String, title: String, channel: String = "") =
        SearchMatcher.score(SearchMatcher.words(query), title, channel)

    @Test fun shortTermDoesNotMatchInsideWords() {
        assertEquals(0, score("pi", "Peppa Pig at the Playground", "Peppa Pig - Official Channel"))
        assertEquals(0, score("pi", "Spider-Man and the Pizza Party"))
    }

    @Test fun shortTermMatchesWholeWord() {
        assertTrue(score("pi", "What is Pi? | Math for Kids") > 0)
        assertTrue(score("pi", "Happy π Day!") > 0)
    }

    @Test fun longerTermMatchesWordPrefix() {
        assertTrue(score("dino", "Dinosaur Songs") > 0)
        assertEquals(0, score("saur", "Dinosaur Songs"))
    }

    @Test fun pluralsFold() {
        assertTrue(score("dinosaurs", "The Biggest Dinosaur Ever") > 0)
        assertTrue(score("dinosaur", "Ten Dinosaurs") > 0)
    }

    @Test fun accentsFold() {
        assertTrue(score("pokemon", "Pokémon Adventures") > 0)
    }

    @Test fun everyTermMustMatch() {
        assertEquals(0, score("pi math", "Peppa Pig"))
        assertEquals(0, score("pi math", "Pi Day Song"))
        assertTrue(score("pi math", "Pi Day Song", "Math Antics") > 0)
    }

    @Test fun titleExactBeatsPrefixBeatsChannel() {
        val exact = score("pig", "Three Little Pigs")
        val prefix = score("pig", "Piglet's Big Day")
        val channel = score("pig", "Muddy Puddles", "Peppa Pig")
        assertTrue(exact > prefix)
        assertTrue(prefix > channel)
        assertTrue(channel > 0)
    }
}
