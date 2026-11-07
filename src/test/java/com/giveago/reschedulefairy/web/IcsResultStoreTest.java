package com.giveago.reschedulefairy.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IcsResultStoreTest {

    @Test
    void evictsOldestResultsWhenOverTheByteBudget() {
        IcsResultStore store = new IcsResultStore(10, 100);
        String first = store.save(new byte[40], "a.ics");
        String second = store.save(new byte[40], "b.ics");
        String third = store.save(new byte[40], "c.ics");

        assertThat(store.find(first)).isEmpty();
        assertThat(store.find(second)).isPresent();
        assertThat(store.find(third)).isPresent();
    }

    @Test
    void evictsOldestResultsWhenOverTheEntryLimit() {
        IcsResultStore store = new IcsResultStore(2, 1_000);
        String first = store.save(new byte[1], "a.ics");
        store.save(new byte[1], "b.ics");
        store.save(new byte[1], "c.ics");

        assertThat(store.find(first)).isEmpty();
    }
}
