package com.ironhack.simple_auth.controller;

import com.ironhack.simple_auth.dto.CommentRequest;
import com.ironhack.simple_auth.dto.CommentView;
import com.ironhack.simple_auth.dto.PostView;
import com.ironhack.simple_auth.dto.SearchResult;
import com.ironhack.simple_auth.model.Comment;
import com.ironhack.simple_auth.model.Post;
import com.ironhack.simple_auth.repository.CommentRepository;
import com.ironhack.simple_auth.repository.PostRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;

    public PostController(PostRepository postRepository, CommentRepository commentRepository) {
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
    }

    /** The full feed. Newest stuff first would be nicer, I know, but this is fine for the lab. */
    @GetMapping
    public List<PostView> all() {
        return postRepository.findAll().stream().map(PostView::from).toList();
    }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam(name = "q", defaultValue = "") String q) {
        return postRepository.findByTitleContainingIgnoreCaseOrBodyContainingIgnoreCase(q, q).stream()
                .map(post -> new SearchResult(post.getId(), post.getTitle(), post.getBody()))
                .toList();
    }

    /** Add a comment to a post. No login, no checks. Anyone can drop one. */
    @PostMapping("/{postId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@PathVariable Long postId, @RequestBody CommentRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        String authorName = (request.authorName() == null || request.authorName().isBlank())
                ? "Anonymous" : request.authorName();

        Comment comment = new Comment(authorName, request.body(), post);
        Comment saved = commentRepository.save(comment);
        return new CommentView(saved.getId(), saved.getAuthorName(), saved.getBody());
    }
}
