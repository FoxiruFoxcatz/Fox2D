set(EXTERNAL_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ExternalLibraries" CACHE PATH "Root folder holding third-party libraries")
option(EXT_VERBOSE "Print detailed layout detection for ExternalLibraries" ON)

# ----------------------------------------------------------------------------
# Every folder under ExternalLibraries/ is classified automatically:
#
#   SOURCE       <lib>/CMakeLists.txt exists      -> add_subdirectory
#   PREBUILT     <lib> has *.so / *.a for the ABI -> imported targets
#   HEADER_ONLY  headers only, no binaries        -> INTERFACE target
#
# Force a mode by creating <lib>/fox_ext.cmake containing:
#   set(FOX_EXT_MODE PREBUILT)        # or SOURCE / HEADER_ONLY
# Pass options to a SOURCE library from the same file:
#   set(FOX_EXT_OPTIONS "BUILD_TESTING=OFF;FOO_SHARED=ON")
# Point a SOURCE/HEADER_ONLY library at a different include root:
#   set(FOX_EXT_INCLUDE_DIRS "${FOX_EXT_ROOT}/src;${FOX_EXT_ROOT}/include")
# Restrict which of a SOURCE lib's targets get linked:
#   set(FOX_EXT_TARGETS "foo;foo_utils")
#
# PREBUILT include layouts:  include/<ABI>  |  <ABI>/include  |  include
# PREBUILT binary layouts:   lib/<ABI>  |  <ABI>/lib  |  libs/<ABI>  |  lib
#
# API:
#   fox_ext_import(<name>)
#   fox_ext_import_all()
#   fox_ext_link(<target> <name> [SHARED|STATIC|AUTO])
#   fox_ext_report()
# ----------------------------------------------------------------------------

set(_FOX_ABI_REGEX "^(arm64-v8a|armeabi-v7a|x86|x86_64)$")

function(_fox_ext_log)
    if(EXT_VERBOSE)
        message(STATUS "[ext] ${ARGN}")
    endif()
endfunction()

function(_fox_ext_first_existing out)
    foreach(c ${ARGN})
        if(IS_DIRECTORY "${c}")
            set(${out} "${c}" PARENT_SCOPE)
            return()
        endif()
    endforeach()
    set(${out} "" PARENT_SCOPE)
endfunction()

# Recursively collect every target created under a directory (for SOURCE libs).
function(_fox_ext_collect_targets dir out)
    get_property(local DIRECTORY "${dir}" PROPERTY BUILDSYSTEM_TARGETS)
    get_property(subs DIRECTORY "${dir}" PROPERTY SUBDIRECTORIES)
    foreach(s ${subs})
        _fox_ext_collect_targets("${s}" nested)
        list(APPEND local ${nested})
    endforeach()
    set(${out} "${local}" PARENT_SCOPE)
endfunction()

function(_fox_ext_has_headers dir out)
    set(found FALSE)
    if(IS_DIRECTORY "${dir}")
        file(GLOB_RECURSE h LIST_DIRECTORIES false RELATIVE "${dir}"
             "${dir}/*.h" "${dir}/*.hpp" "${dir}/*.hh" "${dir}/*.hxx" "${dir}/*.inl")
        if(h)
            set(found TRUE)
        endif()
    endif()
    set(${out} ${found} PARENT_SCOPE)
endfunction()

function(_fox_ext_detect_mode root out)
    if(EXISTS "${root}/fox_ext.cmake")
        set(FOX_EXT_MODE "")
        set(FOX_EXT_ROOT "${root}")
        include("${root}/fox_ext.cmake")
        if(FOX_EXT_MODE)
            set(${out} "${FOX_EXT_MODE}" PARENT_SCOPE)
            return()
        endif()
    endif()

    if(EXISTS "${root}/CMakeLists.txt")
        set(${out} "SOURCE" PARENT_SCOPE)
        return()
    endif()

    _fox_ext_first_existing(ld
        "${root}/lib/${ANDROID_ABI}" "${root}/${ANDROID_ABI}/lib"
        "${root}/libs/${ANDROID_ABI}" "${root}/lib")
    if(ld)
        file(GLOB bins "${ld}/*.so" "${ld}/*.a")
        if(bins)
            set(${out} "PREBUILT" PARENT_SCOPE)
            return()
        endif()
    endif()

    set(${out} "HEADER_ONLY" PARENT_SCOPE)
endfunction()

# ---------------------------------------------------------------- PREBUILT --
function(_fox_ext_import_prebuilt name root)
    _fox_ext_first_existing(inc_dir
        "${root}/include/${ANDROID_ABI}"
        "${root}/${ANDROID_ABI}/include"
        "${root}/include")

    set(inc_mode "none")
    if(inc_dir)
        if(inc_dir STREQUAL "${root}/include/${ANDROID_ABI}")
            set(inc_mode "ABI-split")
        elseif(inc_dir STREQUAL "${root}/${ANDROID_ABI}/include")
            set(inc_mode "ABI-first")
        else()
            set(inc_mode "flat")
            file(GLOB abi_dirs LIST_DIRECTORIES true "${root}/include/*")
            foreach(d ${abi_dirs})
                get_filename_component(dn "${d}" NAME)
                if(IS_DIRECTORY "${d}" AND dn MATCHES "${_FOX_ABI_REGEX}")
                    message(WARNING "[ext] '${name}': include/ has ABI folders but none for '${ANDROID_ABI}'")
                    break()
                endif()
            endforeach()
        endif()
    endif()

    _fox_ext_first_existing(lib_dir
        "${root}/lib/${ANDROID_ABI}" "${root}/${ANDROID_ABI}/lib"
        "${root}/libs/${ANDROID_ABI}" "${root}/lib")

    set(so_files "")
    set(a_files "")
    if(lib_dir)
        file(GLOB so_files "${lib_dir}/*.so")
        file(GLOB a_files  "${lib_dir}/*.a")
    endif()
    list(LENGTH so_files n_so)
    list(LENGTH a_files  n_a)

    if(NOT lib_dir OR (n_so EQUAL 0 AND n_a EQUAL 0))
        message(WARNING "[ext] '${name}': no .so/.a for ABI '${ANDROID_ABI}'")
        return()
    endif()

    if(lib_dir STREQUAL "${root}/lib")
        file(GLOB sub LIST_DIRECTORIES true "${lib_dir}/*")
        foreach(d ${sub})
            get_filename_component(dn "${d}" NAME)
            if(IS_DIRECTORY "${d}" AND dn MATCHES "${_FOX_ABI_REGEX}")
                message(WARNING "[ext] '${name}': lib/ has ABI folders but none for '${ANDROID_ABI}'")
                break()
            endif()
        endforeach()
    endif()

    set(shared_targets "")
    foreach(f ${so_files})
        get_filename_component(base "${f}" NAME_WE)
        string(REGEX REPLACE "^lib" "" short "${base}")
        set(t "ext::${short}")
        if(NOT TARGET ${t})
            add_library(${t} SHARED IMPORTED GLOBAL)
            set_target_properties(${t} PROPERTIES IMPORTED_LOCATION "${f}")
            if(inc_dir)
                set_target_properties(${t} PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${inc_dir}")
            endif()
        endif()
        list(APPEND shared_targets ${t})
    endforeach()

    set(static_targets "")
    foreach(f ${a_files})
        get_filename_component(base "${f}" NAME_WE)
        string(REGEX REPLACE "^lib" "" short "${base}")
        set(t "ext::${short}_static")
        if(NOT TARGET ${t})
            add_library(${t} STATIC IMPORTED GLOBAL)
            set_target_properties(${t} PROPERTIES IMPORTED_LOCATION "${f}")
            if(inc_dir)
                set_target_properties(${t} PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${inc_dir}")
            endif()
        endif()
        list(APPEND static_targets ${t})
    endforeach()

    set(EXT_${name}_MODE     "PREBUILT"          CACHE INTERNAL "")
    set(EXT_${name}_SHARED   "${shared_targets}" CACHE INTERNAL "")
    set(EXT_${name}_STATIC   "${static_targets}" CACHE INTERNAL "")
    set(EXT_${name}_INC_DIR  "${inc_dir}"        CACHE INTERNAL "")
    set(EXT_${name}_LIB_DIR  "${lib_dir}"        CACHE INTERNAL "")
    set(EXT_${name}_INC_MODE "${inc_mode}"       CACHE INTERNAL "")

    _fox_ext_log("${name} [PREBUILT ${ANDROID_ABI}] include=${inc_mode} | ${n_so} .so, ${n_a} .a in ${lib_dir}")
endfunction()

# ------------------------------------------------------------------ SOURCE --
function(_fox_ext_import_source name root)
    set(FOX_EXT_OPTIONS "")
    set(FOX_EXT_INCLUDE_DIRS "")
    set(FOX_EXT_TARGETS "")
    set(FOX_EXT_ROOT "${root}")
    if(EXISTS "${root}/fox_ext.cmake")
        include("${root}/fox_ext.cmake")
    endif()

    # Keep on-device builds fast: disable extras unless the user opted in.
    set(defaults
        BUILD_TESTING=OFF BUILD_TESTS=OFF BUILD_EXAMPLES=OFF BUILD_SAMPLES=OFF
        BUILD_DOCS=OFF BUILD_DOC=OFF BUILD_BENCHMARKS=OFF BUILD_DEMOS=OFF
        BUILD_TOOLS=OFF BUILD_UTILS=OFF BUILD_SHARED_LIBS=OFF)
    # Defaults never override a value the user already set; per-library
    # FOX_EXT_OPTIONS always win.
    foreach(kv ${defaults})
        string(REGEX MATCH "^([^=]+)=(.*)$" _m "${kv}")
        if(CMAKE_MATCH_1 AND NOT DEFINED ${CMAKE_MATCH_1})
            set(${CMAKE_MATCH_1} "${CMAKE_MATCH_2}" CACHE BOOL "" FORCE)
        endif()
    endforeach()
    foreach(kv ${FOX_EXT_OPTIONS})
        string(REGEX MATCH "^([^=]+)=(.*)$" _m "${kv}")
        if(CMAKE_MATCH_1)
            set(_k "${CMAKE_MATCH_1}")
            set(_v "${CMAKE_MATCH_2}")
            if(_v STREQUAL "ON" OR _v STREQUAL "OFF")
                set(${_k} ${_v} CACHE BOOL "" FORCE)
            else()
                set(${_k} "${_v}" CACHE STRING "" FORCE)
            endif()
        endif()
    endforeach()

    _fox_ext_collect_targets("${CMAKE_SOURCE_DIR}" before)
    add_subdirectory("${root}" "${CMAKE_CURRENT_BINARY_DIR}/ext_${name}" EXCLUDE_FROM_ALL)
    _fox_ext_collect_targets("${CMAKE_SOURCE_DIR}" after)

    set(new_targets "")
    foreach(t ${after})
        list(FIND before "${t}" idx)
        if(idx EQUAL -1)
            list(APPEND new_targets ${t})
        endif()
    endforeach()

    if(FOX_EXT_TARGETS)
        set(chosen "${FOX_EXT_TARGETS}")
    else()
        # Keep only real libraries; skip executables, custom targets, tests.
        set(chosen "")
        foreach(t ${new_targets})
            get_target_property(tt ${t} TYPE)
            if(tt MATCHES "^(STATIC_LIBRARY|SHARED_LIBRARY|MODULE_LIBRARY|INTERFACE_LIBRARY|OBJECT_LIBRARY)$")
                if(NOT t MATCHES "(_test|_tests|test_|_example|_bench|_demo)")
                    list(APPEND chosen ${t})
                endif()
            endif()
        endforeach()
    endif()

    # Extra include roots, exposed on each chosen target.
    if(FOX_EXT_INCLUDE_DIRS)
        foreach(t ${chosen})
            get_target_property(tt ${t} TYPE)
            if(tt STREQUAL "INTERFACE_LIBRARY")
                target_include_directories(${t} INTERFACE ${FOX_EXT_INCLUDE_DIRS})
            else()
                target_include_directories(${t} PUBLIC ${FOX_EXT_INCLUDE_DIRS})
            endif()
        endforeach()
    endif()

    if(NOT chosen)
        message(WARNING "[ext] '${name}': add_subdirectory created no linkable targets (set FOX_EXT_TARGETS in ${root}/fox_ext.cmake)")
        return()
    endif()

    set(shared "")
    set(static "")
    foreach(t ${chosen})
        get_target_property(tt ${t} TYPE)
        if(tt STREQUAL "SHARED_LIBRARY")
            list(APPEND shared ${t})
        else()
            list(APPEND static ${t})
        endif()
    endforeach()

    set(EXT_${name}_MODE     "SOURCE"    CACHE INTERNAL "")
    set(EXT_${name}_SHARED   "${shared}" CACHE INTERNAL "")
    set(EXT_${name}_STATIC   "${static}" CACHE INTERNAL "")
    set(EXT_${name}_INC_MODE "from-target" CACHE INTERNAL "")

    _fox_ext_log("${name} [SOURCE] targets: ${chosen}")
endfunction()

# ------------------------------------------------------------- HEADER_ONLY --
function(_fox_ext_import_header_only name root)
    set(FOX_EXT_INCLUDE_DIRS "")
    set(FOX_EXT_ROOT "${root}")
    if(EXISTS "${root}/fox_ext.cmake")
        include("${root}/fox_ext.cmake")
    endif()

    if(FOX_EXT_INCLUDE_DIRS)
        set(inc "${FOX_EXT_INCLUDE_DIRS}")
        set(inc_mode "explicit")
    else()
        _fox_ext_first_existing(cand
            "${root}/include/${ANDROID_ABI}"
            "${root}/include"
            "${root}/single_include"
            "${root}/src")
        if(cand)
            set(inc "${cand}")
            set(inc_mode "auto(${cand})")
        else()
            _fox_ext_has_headers("${root}" rooth)
            if(rooth)
                set(inc "${root}")
                set(inc_mode "auto(root)")
            endif()
        endif()
    endif()

    if(NOT inc)
        message(WARNING "[ext] '${name}': no headers found under ${root}")
        return()
    endif()

    string(REGEX REPLACE "[^A-Za-z0-9_]" "_" safe "${name}")
    set(t "ext::${safe}")
    if(NOT TARGET ${t})
        add_library(${t} INTERFACE IMPORTED GLOBAL)
        set_target_properties(${t} PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${inc}")
    endif()

    set(EXT_${name}_MODE     "HEADER_ONLY" CACHE INTERNAL "")
    set(EXT_${name}_SHARED   ""            CACHE INTERNAL "")
    set(EXT_${name}_STATIC   "${t}"        CACHE INTERNAL "")
    set(EXT_${name}_INC_DIR  "${inc}"      CACHE INTERNAL "")
    set(EXT_${name}_INC_MODE "${inc_mode}" CACHE INTERNAL "")

    _fox_ext_log("${name} [HEADER_ONLY] include=${inc_mode}")
endfunction()

# ---------------------------------------------------------------- PUBLIC ----
function(fox_ext_import name)
    set(root "${EXTERNAL_LIBS_DIR}/${name}")
    if(NOT IS_DIRECTORY "${root}")
        message(WARNING "[ext] '${name}': folder not found at ${root}")
        return()
    endif()

    _fox_ext_detect_mode("${root}" mode)

    if(mode STREQUAL "SOURCE")
        _fox_ext_import_source(${name} "${root}")
    elseif(mode STREQUAL "PREBUILT")
        _fox_ext_import_prebuilt(${name} "${root}")
    elseif(mode STREQUAL "HEADER_ONLY")
        _fox_ext_import_header_only(${name} "${root}")
    else()
        message(WARNING "[ext] '${name}': unknown FOX_EXT_MODE '${mode}'")
    endif()
endfunction()

function(fox_ext_import_all)
    if(NOT IS_DIRECTORY "${EXTERNAL_LIBS_DIR}")
        message(STATUS "[ext] ${EXTERNAL_LIBS_DIR} not found, skipping")
        return()
    endif()
    file(GLOB entries LIST_DIRECTORIES true "${EXTERNAL_LIBS_DIR}/*")
    set(names "")
    foreach(e ${entries})
        if(IS_DIRECTORY "${e}")
            get_filename_component(n "${e}" NAME)
            fox_ext_import(${n})
            list(APPEND names ${n})
        endif()
    endforeach()
    set(EXT_ALL_NAMES "${names}" CACHE INTERNAL "")
endfunction()

function(fox_ext_link target name)
    set(kind "AUTO")
    if(ARGC GREATER 2)
        set(kind "${ARGV2}")
    endif()

    set(sh "${EXT_${name}_SHARED}")
    set(st "${EXT_${name}_STATIC}")

    if(kind STREQUAL "SHARED")
        set(use "${sh}")
    elseif(kind STREQUAL "STATIC")
        set(use "${st}")
    else()
        if(sh)
            set(use "${sh}")
        else()
            set(use "${st}")
        endif()
    endif()

    # A SOURCE lib that only built static should still link if the caller
    # asked for SHARED and nothing shared exists: say so instead of failing.
    if(NOT use AND kind STREQUAL "SHARED" AND st)
        message(STATUS "[ext] '${name}' has no shared build, linking static instead")
        set(use "${st}")
    endif()

    if(NOT use)
        message(WARNING "[ext] cannot link '${name}' (${kind}) to ${target}: nothing imported for ${ANDROID_ABI}")
        return()
    endif()

    target_link_libraries(${target} PRIVATE ${use})
    string(TOUPPER "${name}" upper)
    string(REGEX REPLACE "[^A-Z0-9_]" "_" upper "${upper}")
    target_compile_definitions(${target} PRIVATE "FOX_HAS_${upper}=1")
endfunction()

function(fox_ext_report)
    message(STATUS "[ext] ---- ExternalLibraries report (${ANDROID_ABI}) ----")
    foreach(n ${EXT_ALL_NAMES})
        list(LENGTH EXT_${n}_SHARED ns)
        list(LENGTH EXT_${n}_STATIC nt)
        if(NOT DEFINED EXT_${n}_MODE)
            message(STATUS "[ext]  ${n}: NOT IMPORTED")
        else()
            message(STATUS "[ext]  ${n}: ${EXT_${n}_MODE} | include=${EXT_${n}_INC_MODE} | shared=${ns} static=${nt}")
        endif()
    endforeach()
endfunction()
