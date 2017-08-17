# Change Log
All notable changes to `Nakadi` will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased]

- Enable lz4 compression type for Kafka producer

## [2.0.1] - 2017-08-11

### Fixed
- Added validation of offsets availability when resetting subscription cursors.

### Changed
- Removed authorization for subscription creation

## [2.0.0] - 2017-08-09

### Changed
- Changed imports format to have the same structure

### Removed
- Removed read_scopes and write_scopes from event types
- Removed CHECK_APPLICATION_LEVEL_PERMISSIONS feature

## [1.1.3] - 2017-08-03

### Fixed
- Fixed bug with incorrect lag calculation for subscirption.
- Optimized subscription stats endpoint for subscriptions with many event types inside.

### Changed
- Now it's possible to have a digit after the dot in event-type name.

## [1.1.2] - 2017-08-01

### Changed
- Updated kafka client library to 0.10.1.0

## [1.1.1] - 2017-07-26

### Fixed
- Returned back CHECK_APPLICATION_LEVEL_PERMISSIONS feature toggle.

## [1.1.0] - 2017-07-25

### Added
- The Nakadi manual merged to the project docs.
- The template added to generate Nakadi website using github pages.  
- Addition of a new authentication mode, 'REALM'

### Changed
- The metrics endpoint documentation key "summary" changed to "description" in Open API file.
- Event type authorization refactoring

### Removed
- Removed unused feature toggle CHECK_APPLICATION_LEVEL_PERMISSIONS
  for authorization based on owning_application.

### Fixed
- Fixed formatting of CursorDistanceResult in Open API file.

## [1.0.1] - 2017-07-14

### Fixed
- Fixed log level of InvalidStreamIdException.
- Fixed reading events with Low Level API from event type with expired timeline.

## [1.0.0] - 2017-07-12

### Added
- Authorization on reading from, modifying and publishing to an event type.
