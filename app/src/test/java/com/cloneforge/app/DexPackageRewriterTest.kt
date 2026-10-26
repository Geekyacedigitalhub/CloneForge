package com.cloneforge.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DexPackageRewriterTest {

    private val rewriter = DexPackageRewriter()

    @Test
    fun rewritesExactPackageAndPrefixedNotificationAndProviderStrings() {
        val source = "com.example.chat"
        val clone = "com.example.chat.clone1"

        assertEquals(clone, rewriter.rewritePackageString("com.example.chat", source, clone))
        assertEquals(
            "com.example.chat.clone1.ACTION_NEW_MESSAGE_NOTIFICATION",
            rewriter.rewritePackageString("com.example.chat.ACTION_NEW_MESSAGE_NOTIFICATION", source, clone)
        )
        assertEquals(
            "com.example.chat.clone1.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            rewriter.rewritePackageString("com.example.chat.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", source, clone)
        )
        assertEquals(
            "com.example.chat.clone1.fileprovider",
            rewriter.rewritePackageString("com.example.chat.fileprovider", source, clone)
        )
        assertEquals(
            "content://com.example.chat.clone1.provider/messages",
            rewriter.rewritePackageString("content://com.example.chat.provider/messages", source, clone)
        )
        assertEquals(
            "content://com.example.chat.clone1",
            rewriter.rewritePackageString("content://com.example.chat", source, clone)
        )
    }

    @Test
    fun doesNotDoubleRewriteAlreadyClonedOrUnrelatedStrings() {
        val source = "com.example.chat"
        val clone = "com.example.chat.clone1"

        assertEquals(
            "com.example.chat.clone1.ACTION_NOTIFY",
            rewriter.rewritePackageString("com.example.chat.clone1.ACTION_NOTIFY", source, clone)
        )
        assertEquals(
            "com.example.chatroom.ACTION_NOTIFY",
            rewriter.rewritePackageString("com.example.chatroom.ACTION_NOTIFY", source, clone)
        )
    }
}
