package com.musicmixer.app.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

/**
 * Represents one audio track in the mix.
 * trimStartMs and trimEndMs define the selected region in milliseconds.
 */
data class AudioTrack(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    var trimStartMs: Long = 0L,
    var trimEndMs: Long = durationMs,
    var volumePercent: Int = 100,
    var waveformPeaks: FloatArray = FloatArray(0),
    var colorIndex: Int = 0
) : Parcelable {

    val trimmedDurationMs: Long get() = trimEndMs - trimStartMs

    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: java.util.UUID.randomUUID().toString(),
        uri = parcel.readParcelable(Uri::class.java.classLoader)!!,
        displayName = parcel.readString() ?: "",
        durationMs = parcel.readLong(),
        trimStartMs = parcel.readLong(),
        trimEndMs = parcel.readLong(),
        volumePercent = parcel.readInt(),
        waveformPeaks = FloatArray(parcel.readInt()).also { arr -> parcel.readFloatArray(arr) },
        colorIndex = parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeParcelable(uri, flags)
        parcel.writeString(displayName)
        parcel.writeLong(durationMs)
        parcel.writeLong(trimStartMs)
        parcel.writeLong(trimEndMs)
        parcel.writeInt(volumePercent)
        parcel.writeInt(waveformPeaks.size)
        parcel.writeFloatArray(waveformPeaks)
        parcel.writeInt(colorIndex)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrack) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object CREATOR : Parcelable.Creator<AudioTrack> {
        override fun createFromParcel(parcel: Parcel): AudioTrack = AudioTrack(parcel)
        override fun newArray(size: Int): Array<AudioTrack?> = arrayOfNulls(size)
    }
}
