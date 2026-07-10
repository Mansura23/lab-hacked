# NOTES.md — The Breach Report

This file is part of the deliverable. We grade the **thinking**, not the length.
Fill it in as you work, not at the very end. If you can explain what you did and
why, you have passed, even if your sentences are short.

---

## 1. First impressions

Before attacking anything, write down what the app does and where untrusted input
reaches the backend. Which inputs does a stranger control?

_Your notes:_
InkFeed is a simple community application where users can view drawings/posts, search posts, and add comments. Untrusted input reaches the backend via:
- The search query parameter `q` in `GET /api/posts/search?q=...`
- The comment request payload (`authorName` and `body`) in `POST /api/posts/{postId}/comments`
A stranger controls both the search text and comment fields. Since there is no authentication or authorization, any internet user has direct access to these entry points.

---

## 2. Reproducing the breach

### What I've typed to test the vulnerability and where

I inputted the following payload into the search input box (which translates to the `q` query parameter of the `/api/posts/search` endpoint):

```
%' UNION SELECT id, email, password_hash FROM users -- 
```

### What each part of it does

1. `%'` - Closes the opening single-quote of the pattern match in the SQL query: `title LIKE '%...'`. The first `%` acts as a wildcard for the original match (matching everything), and the `'` terminates the string literal.
2. ` UNION ` - Glues a second query (`SELECT id, email, password_hash FROM users`) onto the first query. Since the database schema requires matching column types and count (3 columns: `Long`/`id`, `String`/`title`, `String`/`body`), we select the matching column types `id`, `email`, and `password_hash` from the `users` table.
3. ` -- ` - Comments out the remainder of the original query (the rest of the `LIKE` clause and the trailing single-quote), preventing H2 from parsing it and throwing a syntax error.

### What came back

The API returned a list containing the original posts (from the first half of the UNION) merged with all entries from the `users` table. Specifically, the admin's email and password hash were exposed:

```json
[
  {
    "id": 6,
    "title": "admin@inkfeed.app",
    "body": "$2a$10$7Qx3rF0kV9pLmN2sT1uWc._aDfGhJkLpQrStUvWxYz0AbCdEfGhIjK"
  }
]
```

---

## 3. Why it worked (root cause)

In your own words: why was the database willing to run that instead of the expected behaviour?

_Your notes:_
The root cause is that the backend dynamically constructs a SQL string by directly concatenating untrusted user input `q` into the query string:
`String sql = "SELECT id, title, body FROM posts WHERE title LIKE '%" + q + "%' OR body LIKE '%" + q + "%'";`
Because the user's input is treated as part of the SQL command instead of a separate data parameter, any special SQL syntax characters (like `'` and `--`) are parsed and executed by the database engine as commands, allowing the attacker to change the query structure and execute arbitrary SQL.


---

## 4. The fix

### Which road did I take?

(parameterized native query / the safe repository method / something else)

_Your notes:_
I took **the safe repository method** road by utilizing the pre-existing derived query method defined in `PostRepository`: `postRepository.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q)`.

### Why this fixes the root cause and not just the symptom

"The error went away" is not an answer. Explain why injection is now impossible,
not just unlikely.

_Your notes:_
Spring Data JPA's derived query methods build a parameterized query under the hood using JDBC `PreparedStatement`s. In a parameterized query, the SQL command template and the user-supplied parameters are sent to the database engine separately. 
The database engine compiles the SQL template first, freezing the query's AST (Abstract Syntax Tree) structure. The parameters are then bound to the placeholders strictly as data values. As a result, the database never parses the bound parameters for SQL commands. Even if the search query contains quotes, comments, or commands like `UNION`, they are treated as literal characters to search for, making SQL injection mathematically and logically impossible.

### Why I did NOT just block quotes / the word UNION

_Your notes:_
Blocklisting is a fragile and fundamentally flawed security strategy for several reasons:
1. **Bypasses**: Attackers can easily bypass simplistic filters using different encodings, syntax features, case manipulation, or database-specific functions.
2. **False Positives**: Banning quotes prevents users from making legitimate queries, such as searching for names like `O'Brien`, contraction words, or quotes in articles.
3. **Defense-in-depth failure**: It shifts the burden of database security to input sanitation, which is error-prone and context-dependent. Parameterization solves the root problem at the database interface layer.

---

## 5. Proof the fix holds

I re-ran my original payload after fixing it. Result:

_Your notes:_
The API returned an empty list `[]` (success). The payload was treated as a literal search query, and since no posts contained the literal string `%' UNION SELECT id, email, password_hash FROM users -- `, no records matched and no data was leaked.

A normal search (`pen`, `color`, `comic`) still returns the right posts:

_Your notes:_
Yes. Searching for `pen` returns the expected post results: "What pen do you swear by?" and "Color theory broke my brain (in a good way)" perfectly.

---

## 6. If I had another hour

What else in this app worries you? (the comment endpoint, the open API, the fact
that the backend can read password hashes at all...)

_Your notes:_
1. **Password Hash Exposure (Least Privilege)**:
   - The application currently selects the `password_hash` column and maps it to the `User` model, which could easily be accidentally leaked. We should ensure the `passwordHash` field is annotated with `@JsonIgnore` or use specialized DTOs/JPA Projections that completely omit the password hash.
   - The database user account used by the search API should not have read access to the `users` table at all, adhering to the principle of least privilege.
2. **Comment Endpoint (XSS & Spam)**:
   - While the comment endpoint is safe from SQL injection (because it uses Hibernate's standard repository `.save()`, which uses prepared statements under the hood), the comment author and body fields are saved without any sanitation. This exposes the application to Stored Cross-Site Scripting (XSS) if the frontend renders these comments without escaping.
   - There is no authorization, CAPTCHA, or rate limiting on comment creation, allowing anyone to spam the database with junk posts.
3. **Blind SQL Injection**:
   - In a vulnerable state, we verified that boolean-based blind SQLi can be performed. By injecting a conditional statement (e.g., checking if `(SELECT COUNT(*) FROM users WHERE email='admin@inkfeed.app') > 0`), the search results would either return all posts (true) or zero posts (false), allowing an attacker to reconstruct the database schema and values one bit at a time, even if the results were not directly rendered.

