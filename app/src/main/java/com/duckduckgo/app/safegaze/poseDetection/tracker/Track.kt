package com.duckduckgo.app.safegaze.poseDetection.tracker

import com.duckduckgo.app.safegaze.poseDetection.Person

data class Track(
    val person: Person,
    val lastTimestamp: Long
)
