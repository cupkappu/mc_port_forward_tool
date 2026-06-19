# GitHub Release Publish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish this Fabric mod repository to GitHub and verify a GitHub Actions release run builds Minecraft 1.20.1 and 1.21.1 jars attached to a release.

**Architecture:** Add a tag/dispatch-triggered GitHub Actions workflow with a matrix build job for the two supported Minecraft versions and a release job that uploads exactly two remapped jars. Keep generated local runtime files out of Git before pushing.

**Tech Stack:** GitHub CLI, GitHub Actions, Gradle, Fabric Loom, Java 21 runner.

---

### Task 1: Prepare Repository For Publishing

**Files:**
- Modify: `.gitignore`
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Ignore generated runtime files**

Add these patterns to `.gitignore`:

```gitignore
run/
*.pyc
__pycache__/
```

- [ ] **Step 2: Remove generated files from Git index without deleting local files**

Run:

```bash
git rm -r --cached run scripts/e2e/__pycache__
```

Expected: `run/...` and `scripts/e2e/__pycache__/...` are staged as deletions, while files remain locally.

### Task 2: Add Release Workflow

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Create workflow**

Create a workflow named `Release` with:

```yaml
on:
  push:
    tags:
      - "v*"
  workflow_dispatch:
```

The `build` job must run `./gradlew -PtargetMinecraft=${{ matrix.minecraft }} build` for `1.20.1` and `1.21.1`, upload one jar per version, and the `release` job must use `gh release upload` to attach exactly two jars.

- [ ] **Step 2: Validate locally**

Run:

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release.yml"); puts "yaml ok"'
scripts/test_matrix.sh
```

Expected: YAML parses and the local matrix passes.

### Task 3: Publish And Verify

**Files:**
- Git remote and GitHub release state

- [ ] **Step 1: Create GitHub repository if absent**

Run:

```bash
gh repo create cupkappu/mc_port_forward_tool --public --source=. --remote=origin --push
```

Expected: `origin` points to `https://github.com/cupkappu/mc_port_forward_tool.git`.

- [ ] **Step 2: Tag and trigger release workflow**

Run:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Expected: a `Release` workflow run starts for tag `v0.1.0`.

- [ ] **Step 3: Confirm action completion and release assets**

Run:

```bash
gh run watch <run-id> --exit-status
gh release view v0.1.0 --json tagName,assets
```

Expected: the workflow completes successfully and the release has exactly:

```text
mc-transport-dialer-1.20.1-0.1.0.jar
mc-transport-dialer-1.21.1-0.1.0.jar
```
