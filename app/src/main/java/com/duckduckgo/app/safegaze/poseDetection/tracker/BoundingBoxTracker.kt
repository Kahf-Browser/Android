package com.duckduckgo.app.safegaze.poseDetection.tracker

import androidx.annotation.VisibleForTesting
import com.duckduckgo.app.safegaze.poseDetection.Person
import kotlin.math.max
import kotlin.math.min

/**
 * BoundingBoxTracker, which tracks objects based on bounding box similarity,
 * currently defined as intersection-over-union (IoU).
 */
class BoundingBoxTracker(config: TrackerConfig = TrackerConfig()) : AbstractTracker(config) {

    /**
     * Computes similarity based on intersection-over-union (IoU). See `AbstractTracker`
     * for more details.
     */
    override fun computeSimilarity(persons: List<Person>): List<List<Float>> {
        if (persons.isEmpty() && tracks.isEmpty()) {
            return emptyList()
        }
        return persons.map { person -> tracks.map { track -> iou(person, track.person) } }
    }

    /**
     * Computes the intersection-over-union (IoU) between a person and a track person.
     * @param person1 A person
     * @param person2 A track person
     * @return The IoU  between the person and the track person. This number is
     * between 0 and 1, and larger values indicate more box similarity.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun iou(person1: Person, person2: Person): Float {
        if (person1.poseBox != null && person2.poseBox != null) {
            val xMin = max(person1.poseBox.left, person2.poseBox.left)
            val yMin = max(person1.poseBox.top, person2.poseBox.top)
            val xMax = min(person1.poseBox.right, person2.poseBox.right)
            val yMax = min(person1.poseBox.bottom, person2.poseBox.bottom)
            if (xMin >= xMax || yMin >= yMax) return 0f
            val intersection = (xMax - xMin) * (yMax - yMin)
            val areaPerson = person1.poseBox.width() * person1.poseBox.height()
            val areaTrack = person2.poseBox.width() * person2.poseBox.height()
            return intersection / (areaPerson + areaTrack - intersection)
        }
        return 0f
    }
}
