#pragma once

#include <cstdint>
#include <memory>

struct FoxMixSnapshot;

std::shared_ptr<FoxMixSnapshot> foxAudioSnapshot();
bool foxAudioHasClips(const FoxMixSnapshot& s);
void foxAudioMix(const FoxMixSnapshot& s, int64_t pos, int frames, float* out);
