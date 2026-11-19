package io.github.currencortex.music.core.update

/** Rank working sources by response time, leaving other rows stable. */
internal fun List<UpdateSource>.sortedByHealth(health: Map<UpdateSource, UpdateSourceHealth>): List<UpdateSource> =
    withIndex().sortedWith(compareBy<IndexedValue<UpdateSource>> {
        if (health[it.value]?.status == UpdateSourceStatus.AVAILABLE) 0 else 1
    }.thenBy {
        if (health[it.value]?.status == UpdateSourceStatus.AVAILABLE) health[it.value]?.latencyMs ?: Long.MAX_VALUE
        else Long.MAX_VALUE
    }.thenBy { it.index }).map { it.value }
