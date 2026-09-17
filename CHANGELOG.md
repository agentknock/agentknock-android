# Changelog

## [0.4.2](https://github.com/agentknock/agentknock-android/compare/v0.4.1...v0.4.2) (2026-09-17)


### Bug Fixes

* finish idle background sync after socket disconnects ([8b71cc3](https://github.com/agentknock/agentknock-android/commit/8b71cc30e994520ee27c78197e0ab66c23305dbd))

## [0.4.1](https://github.com/agentknock/agentknock-android/compare/v0.4.0...v0.4.1) (2026-09-15)


### Bug Fixes

* clarify notification context and visual hierarchy ([fe5b2e2](https://github.com/agentknock/agentknock-android/commit/fe5b2e2f0cc9452d996efd924204fbc3caa6c430))
* keep background request sessions responsive ([b000c84](https://github.com/agentknock/agentknock-android/commit/b000c8446a8203f2dc8a5679839d8d6c6f47541f))

## [0.4.0](https://github.com/agentknock/agentknock-android/compare/v0.3.0...v0.4.0) (2026-09-14)


### Features

* add feedback email action to About ([2041571](https://github.com/agentknock/agentknock-android/commit/20415719b331b39cfe46e74e35134e03eb98314c))
* add offline dependency licenses ([07558ad](https://github.com/agentknock/agentknock-android/commit/07558adcb180a1571113b37b284277369f6d8b80))


### Bug Fixes

* **build:** generate dependency licenses on the JVM ([39b8ec6](https://github.com/agentknock/agentknock-android/commit/39b8ec6ec3160fb1e38a6d0af74e28ddfed69fca))
* give launcher icons more breathing room ([0b17394](https://github.com/agentknock/agentknock-android/commit/0b17394243af5b2f91df55e3c958d5fbfbfbeba0))

## [0.3.0](https://github.com/agentknock/agentknock-android/compare/v0.2.2...v0.3.0) (2026-09-13)


### Features

* add service terms links to setup and settings ([a32e92c](https://github.com/agentknock/agentknock-android/commit/a32e92c01e347491049c7b76323446cc95a96aaf))
* include script contents in approval review ([b4657d6](https://github.com/agentknock/agentknock-android/commit/b4657d6413b2037fa36b46b1e5e605d1e7edcb52))


### Bug Fixes

* keep background AI reviews owned until completion ([318b073](https://github.com/agentknock/agentknock-android/commit/318b0735bd59b0309bfd5c6450968b3351f12061))
* keep subscription management available during outages ([eb55df1](https://github.com/agentknock/agentknock-android/commit/eb55df19ed34c532f412da37b2898df314f21771))
* leave clipboard lifetime to Android ([17388fc](https://github.com/agentknock/agentknock-android/commit/17388fc65995b864529f48d74a8fd940a9dc9a89))
* prefer eligible subscription trials and clarify pricing ([43f45a6](https://github.com/agentknock/agentknock-android/commit/43f45a67ba0fc8085fa36585595b462bf2038c59))
* retain credential authentication across activity recreation ([953499c](https://github.com/agentknock/agentknock-android/commit/953499ce1fd6b458e3faadea0aa90c32605ae3f7))
* tolerate advisory client metadata ([79c41bb](https://github.com/agentknock/agentknock-android/commit/79c41bb481cf665809b14c767ae65044daa48b4e))

## [0.2.2](https://github.com/agentknock/agentknock-android/compare/v0.2.1...v0.2.2) (2026-09-11)


### Bug Fixes

* propagate device key failures from completion handling ([c0aa415](https://github.com/agentknock/agentknock-android/commit/c0aa415b24061bb8991ecfc6767c007a5599bb91))
* scope decrypted device keys to cryptographic operations ([17eaab8](https://github.com/agentknock/agentknock-android/commit/17eaab837d761c892d20d63668e111a114cf2a1e))

## [0.2.1](https://github.com/agentknock/agentknock-android/compare/v0.2.0...v0.2.1) (2026-09-11)


### Bug Fixes

* retry AI review network failures ([ed5815b](https://github.com/agentknock/agentknock-android/commit/ed5815b7751cad9e177b59887b341a40ebdadea1))
* retry temporary AI review failures within 100 seconds ([642f870](https://github.com/agentknock/agentknock-android/commit/642f87044252e37bdf8a79fa34892634137c252e))
* retry temporary AI review failures within 100 seconds ([#6](https://github.com/agentknock/agentknock-android/issues/6)) ([f19fc46](https://github.com/agentknock/agentknock-android/commit/f19fc46be5e3693d18dfafda4a0b31923cc8e05a))
