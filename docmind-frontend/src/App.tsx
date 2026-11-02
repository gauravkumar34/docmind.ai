import React, { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import { useDocumentStore } from './store/documentStore';
import { useConversationStore } from './store/conversationStore';
import { useAuthStore } from './store/authStore';
import Navbar from './components/Navbar';
import Sidebar from './components/Sidebar';
import ChatView from './components/ChatView';
import SearchView from './components/SearchView';
import ChunksView from './components/ChunksView';
import UploadDialog from './components/UploadDialog';
import ProtectedRoute from './components/ProtectedRoute';
import { ErrorBoundary } from './components/ErrorBoundary';
import Login from './pages/Login';
import Register from './pages/Register';

const Dashboard: React.FC = () => {
  const { activeTab, fetchDocuments } = useDocumentStore();
  const { loadChats } = useConversationStore();
  const token = useAuthStore((state) => state.token);
  const user = useAuthStore((state) => state.user);
  const [isUploadOpen, setIsUploadOpen] = useState(false);

  useEffect(() => {
    if (token && user) {
      fetchDocuments();
      loadChats();
    }
  }, [token, user, fetchDocuments, loadChats]);

  return (
    <div className="flex flex-col h-screen overflow-hidden bg-slate-900">
      <Navbar onUploadClick={() => setIsUploadOpen(true)} />

      <div className="flex flex-1 overflow-hidden">
        <Sidebar onUploadClick={() => setIsUploadOpen(true)} />

        {/* Main content */}
        <main className="flex-1 overflow-hidden">
          {activeTab === 'chat' && <ChatView />}
          {activeTab === 'search' && <SearchView />}
          {activeTab === 'chunks' && <ChunksView />}
        </main>
      </div>

      <UploadDialog isOpen={isUploadOpen} onClose={() => setIsUploadOpen(false)} />
    </div>
  );
};

const App: React.FC = () => {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Routes>
        {/* Public Routes */}
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />

        {/* Protected Application Routes */}
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>

      <Toaster
        position="bottom-right"
        toastOptions={{
          style: {
            background: 'var(--slate-800)',
            color: 'var(--slate-100)',
            border: '1px solid var(--slate-700)',
            borderRadius: '12px',
            fontSize: '14px',
          },
          success: {
            iconTheme: { primary: '#22c55e', secondary: 'var(--slate-800)' },
          },
          error: {
            iconTheme: { primary: '#ef4444', secondary: 'var(--slate-800)' },
          },
        }}
      />
    </BrowserRouter>
  </ErrorBoundary>
  );
};

export default App;
