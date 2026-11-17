package app.lynli.klang.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogSnapshotTest {
    private fun page(account: String, vararg ids: String) = JSONObject().put("accountRevision", account)
        .put("items", JSONArray(ids.mapIndexed { index, id -> JSONObject().put("id", id).put("uid", "row-$index") }))
        .put("nextOffset", ids.size)
    @Test fun unchangedFirstPageKeepsScrolledOccurrences() {
        val previous = page("a", "song", "song", "other")
        val fresh = page("a", "song", "song")
        val merged = refreshedCatalogSnapshot(previous, fresh)
        assertEquals(3, merged.getJSONArray("items").length())
        assertEquals("row-1", merged.getJSONArray("items").getJSONObject(1).getString("uid"))
        assertEquals(3, merged.getInt("nextOffset"))
    }
    @Test fun changedOrderOrAccountReplacesSavedPages() {
        val previous = page("a", "one", "two", "three")
        val changed = page("a", "two", "one")
        assertSame(changed, refreshedCatalogSnapshot(previous, changed))
        val otherAccount = page("b", "one", "two")
        assertSame(otherAccount, refreshedCatalogSnapshot(previous, otherAccount))
    }
}
