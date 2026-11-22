package app.vela.core.model

/**
 * Which saved lists draw on the map (issue #988). A list switched off ([PlaceList.onMap] false)
 * draws no pins and no shapes, unless it is the open list. Search, the lists sheet and the place
 * sheet's list picker read every list.
 */
object ListsOnMap {
    /** The lists that draw: each one switched on, and [openListId] whatever its switch. [lists]
     *  itself when every list is on. */
    fun shown(lists: List<PlaceList>, openListId: String?): List<PlaceList> =
        if (lists.all { it.onMap }) lists else lists.filter { it.onMap || it.id == openListId }

    /** One pin of the saved-places layer: a list's place drawn for [Listed.list], or a quick-save. */
    sealed interface Pin {
        data class Listed(val place: ListPlace, val list: PlaceList) : Pin
        data class Saved(val place: SavedPlace) : Pin
    }

    /**
     * The saved-places layer in draw order. Each place of a [shown] list draws once, for the first
     * list in the lists' order that holds it, less a custom map's hidden layers. A place that is
     * also in a list switched off still draws for the list that is on. Then each quick-save no list
     * drew: a quick-save is not a list and has no switch.
     */
    fun pins(lists: List<PlaceList>, saved: List<SavedPlace>, openListId: String?): List<Pin> {
        val seen = HashSet<String>()
        val out = ArrayList<Pin>()
        for (l in shown(lists, openListId)) {
            for (p in l.places) {
                if (p.layer != null && p.layer in l.hiddenLayers) continue
                if (seen.add(p.id)) out += Pin.Listed(p, l)
            }
        }
        for (s in saved) if (seen.add(s.id)) out += Pin.Saved(s)
        return out
    }
}
