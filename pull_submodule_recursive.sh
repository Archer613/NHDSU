#!/bin/bash

pull_submodule_recursive()
{
    if [ -f ".gitmodules" ];then
        echo ".gitmodules found"
        # backup
        cp .gitmodules .gitmodules.bak
        while read line
        do
            # substitude the https with ssh
            echo ${line} | sed 's/https:\/\/github.com\//git@github.com:/g' >> .new_gitmodules
        done < .gitmodules
        mv .new_gitmodules .gitmodules
        # pull current submodules
        git submodule init
        git submodule sync
        git submodule update
        # mv backup
        rm .gitmodules
        mv .gitmodules.bak .gitmodules
        # get the directories of current submodules
        local directories=$(cat .gitmodules | grep path | awk '{print $3}')
        for directory in $directories
        do
            if [ -d $directory ];then
                # enter the directory
                pushd ${directory} > /dev/null
                # pull one submodule and its submodules
                pull_submodule_recursive
                # return to the last working directory
                popd > /dev/null
            fi
        done
    else
        echo "current submodule has no submodule, return to last directory..."
    fi
    return 0
}

pull_submodule_recursive
