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

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();

    if target != "aarch64-linux-android" {
        panic!(
            "\n\n[Error] This project is strictly for Android ARM64.\n\
            Target tried: '{}'\n\
            Target expected: 'aarch64-linux-android'\n\
            Use: cargo ndk -t arm64-v8a build --release\n\n",
            target
        );
    }
}
