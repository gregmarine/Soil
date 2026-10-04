package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract

/** Whether this export goes to a folder, one file per page. */
object ExportDelivery {
    fun perPage(delivery: Int, scope: ExportScope): Boolean =
        delivery == ExportContract.DELIVERY_PER_PAGE && scope is ExportScope.Whole
}
