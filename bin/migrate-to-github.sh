#!/usr/bin/env bash

# Script to convert permissions files from Jira to GitHub issues
# Usage: ./migrate-to-github.sh <filepath> [<filepath2> ...]
# Example: ./migrate-to-github.sh permissions/plugin-durable-task.yml permissions/plugin-timestamper.yml

if [ $# -eq 0 ]; then
    echo "Usage: $0 <filepath> [<filepath2> ...]"
    echo "Example: $0 permissions/plugin-durable-task.yml permissions/plugin-timestamper.yml"
    exit 1
fi

# Array to collect errors
declare -a errors=()
declare -a skipped=()
declare -a processed=()

# Function to process a single file
process_file() {
    local filepath="$1"
    
    if [ ! -f "$filepath" ]; then
        errors+=("File '$filepath' not found")
        return 1
    fi

    # Check if file has github field and jira in issues section
    if ! grep -q "^github:" "$filepath"; then
        errors+=("File '$filepath' must have a 'github:' field")
        return 1
    fi

    if ! grep -q "jira:" "$filepath"; then
        skipped+=("$filepath: No Jira reference found")
        return 0
    fi

    # Check if already has anchor and extract the anchor name
    local has_github_anchor=false
    local anchor_name="gh"
    local github_line=$(grep "^github:" "$filepath" | head -1)
    
    if echo "$github_line" | grep -qE "^github: *&[A-Za-z0-9_]+"; then
        has_github_anchor=true
        anchor_name=$(echo "$github_line" | sed 's/^github: *&//' | sed 's/ .*//')
    fi

    # Extract github repository (remove quotes and anchor references)
    local github_repo=$(echo "$github_line" | sed 's/^github: *&[A-Za-z0-9_]* *//' | sed 's/^github: *//' | sed 's/"//g' | tr -d "'")

    if [ -z "$github_repo" ]; then
        errors+=("$filepath: Could not extract GitHub repository")
        return 1
    fi

    echo "Converting $filepath to use GitHub issues for repo: $github_repo"

    # Extract plugin name from the name attribute in the yaml file
    local plugin_name=$(grep "^name:" "$filepath" | head -1 | sed 's/^name: *//' | sed 's/"//g' | tr -d "'")

    if [ -z "$plugin_name" ]; then
        errors+=("$filepath: Could not extract plugin name")
        return 1
    fi

    # Extract jira component name from comment (after #) before we remove it
    local jira_line=$(grep -A 1 "^issues:" "$filepath" | grep "jira:")
    local jira_component=""
    if echo "$jira_line" | grep -q "#"; then
        jira_component=$(echo "$jira_line" | sed 's/.*# *//' | sed 's/ *$//')
    fi

    # Detect whether a GitHub issues entry already exists inside the issues block
    local has_github_issue=$(awk '
        /^issues:/ { in_issues = 1; next }
        in_issues && /^  - github:/ { print "true"; exit }
        in_issues && $0 !~ /^  / { exit }
    ' "$filepath")

    if [ -z "$has_github_issue" ]; then
        has_github_issue="false"
    fi

    # Create a temporary file
    local tmpfile=$(mktemp)

    # Process the file
    awk -v gh_repo="$github_repo" -v has_anchor="$has_github_anchor" -v anchor="$anchor_name" -v github_exists="$has_github_issue" '
BEGIN {
    in_issues = 0
    added_github = 0
    skip_next = 0
    existing_github = (github_exists == "true")
}

/^github:/ {
    if (has_anchor == "true") {
        print $0
    } else {
        print "github: &" anchor " \"" gh_repo "\""
    }
    next
}

/^issues:/ {
    in_issues = 1
    print $0
    next
}

in_issues {
    if (skip_next && $0 ~ /^    /) {
        next
    }
    skip_next = 0
    
    if ($0 ~ /^  - github:/) {
        existing_github = 1
    }
    
    if ($0 ~ /^  - jira:/) {
        if (!existing_github && !added_github) {
            print "  - github: *" anchor
            added_github = 1
            existing_github = 1
        }
        skip_next = 1
        next
    }
    if ($0 !~ /^  /) {
        in_issues = 0
    }
}

{
    print $0
}
' "$filepath" > "$tmpfile"

    # Replace original file with modified version
    mv "$tmpfile" "$filepath"

    echo "Successfully converted $filepath"
    processed+=("$filepath|$plugin_name|$github_repo|$jira_component")
    return 0
}

# Process all files
echo "Processing ${#@} file(s)..."
for filepath in "$@"; do
    process_file "$filepath"
done

# If we processed any files, create branch, commit, and PR
if [ ${#processed[@]} -gt 0 ]; then
    # Create branch name based on first file or use a generic name for multiple files
    if [ ${#processed[@]} -eq 1 ]; then
        first_file="${processed[0]%%|*}"
        branch_name="migrate-to-github-$(basename "$first_file" .yml)"
    else
        branch_name="migrate-to-github-batch-$(date +%s)"
    fi

    git checkout master
    git pull --no-rebase
    git checkout -b "$branch_name"
    
    # Add all processed files
    for entry in "${processed[@]}"; do
        filepath="${entry%%|*}"
        git add "$filepath"
    done
    
    # Create commit message
    if [ ${#processed[@]} -eq 1 ]; then
        plugin_name=$(echo "${processed[0]}" | cut -d'|' -f2)
        commit_msg="Migrate $plugin_name plugin issues to GitHub"
    else
        commit_msg="Migrate ${#processed[@]} plugins issues to GitHub"
    fi
    
    git commit -m "$commit_msg"
    
    echo "Committed changes"
    
    # Push the current branch
    current_branch=$(git branch --show-current)
    git push -u fork "$current_branch"
    
    # Create draft PR using GitHub CLI
    if [ ${#processed[@]} -eq 1 ]; then
        plugin_name=$(echo "${processed[0]}" | cut -d'|' -f2)
        pr_title="Migrate $plugin_name plugin issues to GitHub"
    else
        pr_title="Migrate ${#processed[@]} plugins issues to GitHub"
    fi
    
    # Build plugin list for PR body
    plugin_list=""
    for entry in "${processed[@]}"; do
        plugin_name=$(echo "$entry" | cut -d'|' -f2)
        plugin_list+="- $plugin_name"$'\n'
    done
    
    # Build mapping for PR body (commented out)
    mapping_list=""
    for entry in "${processed[@]}"; do
        plugin_name=$(echo "$entry" | cut -d'|' -f2)
        github_repo=$(echo "$entry" | cut -d'|' -f3)
        jira_component=$(echo "$entry" | cut -d'|' -f4)
        
        # Extract just the repo name from the full path (e.g., jenkinsci/slack-plugin -> slack-plugin)
        repo_name=$(echo "$github_repo" | sed 's/.*\///')
        
        if [ -n "$jira_component" ]; then
            mapping_list+="$jira_component:$repo_name"$'\n'
        fi
    done
    
    pr_body="Hi

Now that all the core components are migrated to GitHub from Jira, I'd like to start migrating plugins. 

Plugins being migrated:
$plugin_list
@ for approval

Let me know if any objections or questions

<!--
Jira to GitHub mapping:
$mapping_list
-->"
    
    echo "Creating draft PR..."
    gh pr create --title "$pr_title" --body "$pr_body" --web
fi

# Output summary
echo ""
echo "=========================================="
echo "Summary:"
echo "=========================================="
echo "Processed: ${#processed[@]} file(s)"
echo "Skipped: ${#skipped[@]} file(s)"
echo "Errors: ${#errors[@]}"

if [ ${#skipped[@]} -gt 0 ]; then
    echo ""
    echo "Skipped files:"
    for skip in "${skipped[@]}"; do
        echo "  - $skip"
    done
fi

if [ ${#errors[@]} -gt 0 ]; then
    echo ""
    echo "Errors encountered:"
    for error in "${errors[@]}"; do
        echo "  - $error"
    done
    exit 1
fi

echo ""
echo "Migration completed successfully!"

echo "PR created and opened in browser"
