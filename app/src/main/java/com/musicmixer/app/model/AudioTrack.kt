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
    var colorIndex: Int = 0,
    /** Silence (ms) inserted before this clip during export */
    var delayBeforeMs: Long = 0L,
    /** Silence (ms) inserted after this clip during export */
    var delayAfterMs: Long = 0L
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
        colorIndex = parcel.readInt(),
        delayBeforeMs = parcel.readLong(),
        delayAfterMs = parcel.readLong()
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
        parcel.writeLong(delayBeforeMs)
        parcel.writeLong(delayAfterMs)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTrack) return false
        return id            == other.id            &&
               displayName   == other.displayName   &&
               trimStartMs   == other.trimStartMs   &&
               trimEndMs     == other.trimEndMs     &&
               volumePercent == other.volumePercent &&
               delayBeforeMs == other.delayBeforeMs &&
               delayAfterMs  == other.delayAfterMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + trimStartMs.hashCode()
        result = 31 * result + trimEndMs.hashCode()
        result = 31 * result + volumePercent
        return result
    }

    companion object CREATOR : Parcelable.Creator<AudioTrack> {
        override fun createFromParcel(parcel: Parcel): AudioTrack = AudioTrack(parcel)
        override fun newArray(size: Int): Array<AudioTrack?> = arrayOfNulls(size)
    }
}
