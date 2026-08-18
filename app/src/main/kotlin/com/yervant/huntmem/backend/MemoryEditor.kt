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
import org.json.JSONArray
import org.json.JSONObject

class MemoryEditor {

    fun writeAll(addrs: List<AddressInfo>, value: String) {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return

        val arr = JSONArray()
        for (a in addrs) {
            val obj = JSONObject()
            obj.put("address", a.matchInfo.address)
            obj.put("value", value)
            obj.put("value_type", a.matchInfo.valueType)
            arr.put(obj)
        }
        NativeBridge.batchWrite(pid, arr.toString())
    }

    fun freezeAddress(addressInfo: AddressInfo) {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return
        val res = NativeBridge.freezeAddress(
            pid = pid,
            address = addressInfo.matchInfo.address,
            value = addressInfo.matchInfo.prevValue.toString(),
            valueType = addressInfo.matchInfo.valueType
        )
        if (res == 0) {
            addressInfo.isFrozen = true
        }
    }

    fun unfreezeAddress(addressInfo: AddressInfo) {
        NativeBridge.unfreezeAddress(addressInfo.matchInfo.address)
        addressInfo.isFrozen = false
    }

    fun freezeAll(addressList: List<AddressInfo>, value: String) {
        val pid = AttachedProcessRepository.getAttachedPid() ?: return
        addressList.forEach { addressInfo ->
            val res = NativeBridge.freezeAddress(
                pid = pid,
                address = addressInfo.matchInfo.address,
                value = value,
                valueType = addressInfo.matchInfo.valueType
            )
            if (res == 0) {
                addressInfo.isFrozen = true
            }
        }
    }

    fun unfreezeAll(addressList: List<AddressInfo>) {
        NativeBridge.unfreezeAll()
        addressList.forEach { it.isFrozen = false }
    }

    fun syncFreezeState(addressList: List<AddressInfo>) {
        addressList.forEach { addressInfo ->
            val isFrozen = NativeBridge.isAddressFrozen(addressInfo.matchInfo.address)
            if (addressInfo.isFrozen != isFrozen) {
                addressInfo.isFrozen = isFrozen
            }
        }
    }
}
