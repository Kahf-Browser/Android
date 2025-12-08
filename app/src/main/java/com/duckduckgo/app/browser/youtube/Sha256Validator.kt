/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser.youtube

import timber.log.Timber
import java.security.MessageDigest

/**
 * Utility for validating file integrity using SHA-256 checksums
 *
 * Ensures downloaded files haven't been tampered with by comparing
 * their computed hash against an expected hash value.
 */
object Sha256Validator {

    /**
     * Validate a string against an expected SHA-256 hash
     *
     * @param content The content to validate
     * @param expectedHash The expected SHA-256 hash (64-character hex string)
     * @return true if the computed hash matches the expected hash
     *
     * Example:
     * ```
     * val isValid = validate(
     *     content = "console.log('hello')",
     *     expectedHash = "8d3257c0325d1eb4936d1b76c260f3ba066d9cc0b5578b4985e47c296c0c032f"
     * )
     * ```
     */
    fun validate(content: String, expectedHash: String): Boolean {
        return try {
            val computedHash = computeSha256(content)
            val isValid = computedHash.equals(expectedHash, ignoreCase = true)

            if (!isValid) {
                Timber.w(
                    "SHA-256 validation failed:\n" +
                        "  Expected: $expectedHash\n" +
                        "  Computed: $computedHash"
                )
            }

            isValid
        } catch (e: Exception) {
            Timber.e(e, "Error during SHA-256 validation")
            false
        }
    }

    /**
     * Compute SHA-256 hash of a string
     *
     * @param input The string to hash
     * @return The SHA-256 hash as a lowercase hex string
     */
    fun computeSha256(input: String): String {
        return try {
            val bytes = input.toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(bytes)

            // Convert byte array to hex string
            hashBytes.joinToString("") { byte ->
                "%02x".format(byte)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error computing SHA-256")
            ""
        }
    }
}
