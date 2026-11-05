package io.horizontalsystems.stellarkit.room

import io.horizontalsystems.stellarkit.TagQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Must agree with OperationDao filtering: null query fields mean "any value".
class TagConformsTest {

    private val tag = tag()

    @Test
    fun conforms_emptyQuery_matches() {
        assertTrue(tag.conforms(TagQuery(null, null, null)))
    }

    @Test
    fun conforms_assetOnlyQuery_matchingAsset_matches() {
        assertTrue(tag.conforms(TagQuery(null, ASSET, null)))
    }

    @Test
    fun conforms_assetOnlyQuery_otherAsset_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(null, "OTHER", null)))
    }

    @Test
    fun conforms_typeOnlyQuery_matchingType_matches() {
        assertTrue(tag.conforms(TagQuery(Tag.Type.Incoming, null, null)))
    }

    @Test
    fun conforms_typeOnlyQuery_otherType_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(Tag.Type.Outgoing, null, null)))
    }

    @Test
    fun conforms_typeOnlyQuery_tagWithoutType_doesNotMatch() {
        assertFalse(tag(type = null).conforms(TagQuery(Tag.Type.Incoming, null, null)))
    }

    @Test
    fun conforms_accountOnlyQuery_containedAccount_matches() {
        assertTrue(tag.conforms(TagQuery(null, null, ACCOUNT)))
    }

    @Test
    fun conforms_accountOnlyQuery_notContainedAccount_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(null, null, "OTHER")))
    }

    @Test
    fun conforms_fullQuery_allFieldsMatch_matches() {
        assertTrue(tag.conforms(TagQuery(Tag.Type.Incoming, ASSET, ACCOUNT)))
    }

    @Test
    fun conforms_fullQuery_mismatchingType_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(Tag.Type.Swap, ASSET, ACCOUNT)))
    }

    @Test
    fun conforms_fullQuery_mismatchingAsset_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(Tag.Type.Incoming, "OTHER", ACCOUNT)))
    }

    @Test
    fun conforms_fullQuery_mismatchingAccount_doesNotMatch() {
        assertFalse(tag.conforms(TagQuery(Tag.Type.Incoming, ASSET, "OTHER")))
    }

    private fun tag(type: Tag.Type? = Tag.Type.Incoming) =
        Tag(operationId = 1, type = type, assetId = ASSET, accountIds = listOf(ACCOUNT))

    private companion object {
        const val ASSET = "native"
        const val ACCOUNT = "GACCOUNT"
    }
}
