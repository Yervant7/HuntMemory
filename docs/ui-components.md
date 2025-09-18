---
layout: default
title: UI Components
---

# UI Components

HuntMemory features a comprehensive user interface designed for efficient memory analysis and manipulation. This document describes each UI component and how to use them effectively.

## Process Menu

The Process Menu is where you select and attach to a running application for memory analysis.

### Features
- **Process List**: Displays all running processes with their package names and memory usage
- **Search**: Filter processes by package name
- **Refresh**: Update the process list
- **Attachment Indicator**: Shows the currently attached process

### Usage
1. Browse or search for the target process
2. Tap on a process to attach to it
3. The attachment indicator will update to show the attached process

## Memory Menu

The Memory Menu is used for scanning and filtering memory values in the attached process.

### Features
- **Tab-based Interface**: Manage multiple scan sessions
- **Value Scanning**: Search for specific values in memory
- **Value Filtering**: Narrow down results using different operators
- **Address Navigation**: Jump to specific memory addresses
- **Pointer Scanning**: Find pointers to values
- **Match List**: View and manage found memory addresses

### Scanning Workflow
1. Enter a value to search for
2. Select the appropriate value type (int, long, float, double)
3. Click "New Scan" for initial search
4. Refine results by entering new values and using "Refine"
5. Save interesting addresses to the Address Table

### Advanced Features
- **Pointer Search**: Find pointers to your current results
- **Dereference Pointers**: Follow pointers to their target values
- **Address Navigation**: Jump directly to specific memory addresses

## Address Table

The Address Table manages saved memory addresses for ongoing manipulation.

### Features
- **Address List**: View saved addresses with their current values
- **Value Editing**: Modify values in real-time
- **Freeze Toggle**: Lock values to prevent changes
- **Batch Operations**: Edit or freeze multiple addresses at once

### Operations
- **Edit Value**: Tap on a value to modify it
- **Freeze**: Toggle the switch to freeze/unfreeze a value
- **Delete**: Tap on an address to remove it
- **Batch Edit**: Use "Edit All" to change multiple values
- **Batch Freeze**: Use "Freeze All" to freeze multiple values
- **Batch Unfreeze**: Use "Unfreeze All" to unfreeze multiple values
- **Delete All**: Remove all addresses from the table

## Script Menu

The Script Menu manages Lua scripts for automation and advanced functionality.

### Features
- **Script List**: View all available scripts
- **Script Execution**: Run scripts with a tap
- **Script Management**: Delete scripts when no longer needed

### Usage
1. Import .lua files
2. Scripts will appear in the list
3. Tap the play button to execute a script
4. Use the delete button to remove scripts

## Settings Menu

The Settings Menu configures memory scanning preferences.

### Features
- **Memory Region Selection**: Choose which memory regions to scan
- **Custom Region Filtering**: Specify custom memory regions
- **Menu Navigation**: Switch between different app menus

### Memory Regions
- **ALLOC**: Allocated memory regions
- **BSS**: Uninitialized data segment
- **DATA**: Initialized data segment
- **HEAP**: Heap memory
- **JAVA_HEAP**: Java heap memory
- **ANONYMOUS**: Anonymous memory mappings
- **STACK**: Stack memory
- **CODE_SYSTEM**: System code regions
- **ASHMEM**: Android shared memory
- **LIBS**: Library regions
- **CUSTOM**: User-defined regions

## Dynamic Menu

The Dynamic Menu is a floating UI that can be created and managed through Lua scripts.

### Components
- **Labels**: Display text information
- **Buttons**: Trigger actions when clicked
- **Switches**: Toggle boolean values
- **Sliders**: Adjust numeric values within a range

### Usage
Dynamic menus are created and managed entirely through the Lua API. See the [Lua API Reference](lua-api.md) for details on creating and managing menu components.