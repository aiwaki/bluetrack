```markdown
# bluetrack Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill teaches the core development patterns and conventions used in the `bluetrack` Swift codebase. You'll learn how to structure files, write imports and exports, and follow the repository's conventions for naming and testing. While no specific frameworks or automated workflows were detected, this guide will help you maintain consistency and quality in your contributions.

## Coding Conventions

### File Naming
- Use **PascalCase** for all file names.
  - **Example:** `UserProfile.swift`, `NetworkManager.swift`

### Import Style
- Use **relative imports** when referencing modules or files.
  - **Example:**
    ```swift
    import ../Utilities/Logger
    ```

### Export Style
- Use **named exports** to expose specific classes, structs, or functions.
  - **Example:**
    ```swift
    public class UserProfile { ... }
    public func fetchData() { ... }
    ```

### Commit Messages
- Freeform commit messages, average length ~59 characters.
  - **Example:**  
    `Fix bug in user authentication flow`

## Workflows

### Adding a New Feature
**Trigger:** When implementing a new feature or module  
**Command:** `/add-feature`

1. Create a new Swift file using PascalCase (e.g., `NewFeature.swift`).
2. Use relative imports to include any dependencies.
3. Export your classes or functions using named exports.
4. Write or update corresponding test files (`NewFeature.test.swift`).
5. Commit changes with a clear, descriptive message.

### Fixing a Bug
**Trigger:** When resolving a bug or issue  
**Command:** `/fix-bug`

1. Locate the affected file(s).
2. Apply the fix, following coding conventions.
3. Update or add relevant tests in `*.test.swift` files.
4. Commit with a message describing the bug fix.

### Writing Tests
**Trigger:** When adding or updating tests  
**Command:** `/write-test`

1. Create or update a test file matching the pattern `*.test.swift`.
2. Implement test cases for your feature or bug fix.
3. Ensure tests cover edge cases and expected behaviors.
4. Run tests using the project's preferred method (framework unknown).

## Testing Patterns

- Test files follow the pattern: `*.test.swift`
- The specific testing framework is not detected; use standard Swift testing practices.
- Place tests near the code they cover or in a dedicated `Tests` directory.
- Example test file:
  ```swift
  import XCTest
  import ../UserProfile

  class UserProfileTests: XCTestCase {
      func testProfileInitialization() {
          let profile = UserProfile(name: "Alice")
          XCTAssertEqual(profile.name, "Alice")
      }
  }
  ```

## Commands
| Command      | Purpose                                      |
|--------------|----------------------------------------------|
| /add-feature | Scaffold and implement a new feature/module  |
| /fix-bug     | Apply and commit a bug fix                   |
| /write-test  | Add or update tests for code changes         |
```
