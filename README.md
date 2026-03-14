# Disk Analyzer

A Java Swing desktop application that scans a disk drive and visualises folder sizes.

## Features

- **Drive selector** — dropdown lists all mounted drives with used/free/total space
- **Left panel — Directory Tree**
  - Shows every folder with its total size (including sub-folders)
  - Colour-coded by relative size (red → large, yellow → medium, green → small)
  - Sort by **Size** (default) or by **Name**
  - Expand All / Collapse All controls
  - Tooltips with full path and file count
- **Right panel — Pie Chart**
  - Top-12 largest folders visualised via JFreeChart
  - "Files / Others" slice for the remainder
  - Summary table with rank, name, size and % of drive
- **Status bar** — live scanning progress + selected-node details
- **Cancel** — stops the background worker at any time
- **FlatLaf dark theme**

## Requirements

- Java 17 or newer (JDK, not just JRE)
- Internet access on first build (Gradle downloads dependencies from Maven Central)

## Build & Run

### Build a fat JAR
```bash
gradle clean jar
java -jar build/libs/disk-analyzer.jar
```
