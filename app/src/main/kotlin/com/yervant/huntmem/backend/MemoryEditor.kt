/*
 * HuntMemory - Process Memory Editor & Scanner for Android
 * Copyright (C) 2026 Yervant7
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yervant.huntmem.backend

import com.yervant.huntmem.ui.overlay.tabs.AddressInfo

class MemoryEditor {

    fun writeAll(addrs: List<AddressInfo>, value: String): Int {
        if (NativeBridge.getHmkpmVersionInfo().isLockless) return 0
        val pid = AttachedProcessRepository.getAttachedPid() ?: return 0
        if (addrs.isEmpty()) return 0

        val encodedWrites = mutableListOf<Pair<Long, ByteArray>>()
        for (a in addrs) {
            val bytes = NativeBridge.valueStringToBytes(value, a.matchInfo.valueType)
            if (bytes != null) {
                encodedWrites.add(Pair(a.matchInfo.address, bytes))
            }
        }
        if (encodedWrites.isEmpty()) return 0

        val payload = NativeBridge.encodeBatchWrites(encodedWrites)
        return NativeBridge.batchWriteBinary(pid, payload)
    }

    fun freezeAddress(addressInfo: AddressInfo): Boolean {
        if (NativeBridge.getHmkpmVersionInfo().isLockless) return false
        val pid = AttachedProcessRepository.getAttachedPid() ?: return false
        val res = NativeBridge.freezeAddress(
            pid = pid,
            address = addressInfo.matchInfo.address,
            value = addressInfo.matchInfo.prevValue.toString(),
            valueType = addressInfo.matchInfo.valueType
        )
        return res == 0
    }

    fun unfreezeAddress(addressInfo: AddressInfo): Boolean {
        return NativeBridge.unfreezeAddress(addressInfo.matchInfo.pid, addressInfo.matchInfo.address)
    }

    fun freezeAll(addressList: List<AddressInfo>, value: String) {
        if (NativeBridge.getHmkpmVersionInfo().isLockless) return
        val pid = AttachedProcessRepository.getAttachedPid() ?: return
        addressList.forEach { addressInfo ->
            NativeBridge.freezeAddress(
                pid = pid,
                address = addressInfo.matchInfo.address,
                value = value,
                valueType = addressInfo.matchInfo.valueType
            )
        }
    }

    fun unfreezeAll(addressList: List<AddressInfo>) {
        NativeBridge.unfreezeAll()
    }
}
