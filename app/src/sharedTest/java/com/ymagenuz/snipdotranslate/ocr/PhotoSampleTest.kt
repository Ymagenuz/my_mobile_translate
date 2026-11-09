package com.ymagenuz.snipdotranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSampleTest {
    @Test
    fun smallPicturesAreNotReduced() {
        assertEquals(1, photoSampleSize(1080, 2400))
        assertEquals(1, photoSampleSize(4000, 3000))
    }

    @Test
    fun largePicturesAreReducedWithoutGoingBelowTheLimit() {
        assertEquals(2, photoSampleSize(4800, 3600))
        assertEquals(4, photoSampleSize(12000, 9000))
    }

    @Test
    fun portraitAndLandscapeAreTreatedAlike() {
        assertEquals(photoSampleSize(9000, 12000), photoSampleSize(12000, 9000))
    }
}
