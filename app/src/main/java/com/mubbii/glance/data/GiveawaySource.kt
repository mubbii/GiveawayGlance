package com.mubbii.glance.data

import com.mubbii.glance.model.GiveawayItem

/**
 * Anything that can report "here's what's relevant right now" implements
 * this. Most sources (forum threads) only ever have one relevant item —
 * the latest post — but some (Lenovo's key drops page) can have several
 * things live at once (an Active drop AND a Coming Soon drop), so this
 * returns a list rather than a single item.
 */
interface GiveawaySource {
    val key: String    // stable key used for last-seen tracking (SeenStore)
    val name: String    // shown on the card as the source chip
    fun fetchLatest(): List<GiveawayItem>
}
