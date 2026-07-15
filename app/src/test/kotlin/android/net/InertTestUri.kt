// SPDX-License-Identifier: GPL-3.0-or-later

package android.net

import android.os.Parcel

/** Non-dereferenceable local-unit-test value for collaborators that fake all URI I/O. */
internal class InertTestUri : Uri() {
    override fun buildUpon(): Builder = error("The inert test URI must not be dereferenced")

    override fun getAuthority(): String? = null

    override fun getEncodedAuthority(): String? = null

    override fun getEncodedFragment(): String? = null

    override fun getEncodedPath(): String? = null

    override fun getEncodedQuery(): String? = null

    override fun getEncodedSchemeSpecificPart(): String = ""

    override fun getEncodedUserInfo(): String? = null

    override fun getFragment(): String? = null

    override fun getHost(): String? = null

    override fun getLastPathSegment(): String? = null

    override fun getPath(): String? = null

    override fun getPathSegments(): List<String> = emptyList()

    override fun getPort(): Int = -1

    override fun getQuery(): String? = null

    override fun getScheme(): String? = null

    override fun getSchemeSpecificPart(): String = ""

    override fun getUserInfo(): String? = null

    override fun isHierarchical(): Boolean = false

    override fun isRelative(): Boolean = true

    override fun describeContents(): Int = 0

    override fun writeToParcel(destination: Parcel, flags: Int) = Unit

    override fun toString(): String = "inert-test-uri"
}
